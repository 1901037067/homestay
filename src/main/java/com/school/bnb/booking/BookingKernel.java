package com.school.bnb.booking;

import com.school.bnb.common.AuthContext;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Occupancy;
import com.school.bnb.entity.Room;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.mapper.OccupancyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.vo.HoldCandidate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预订内核 —— 本项目唯一的正确性来源（实施计划第 3 节）。
 *
 * <p><b>三条铁律，改这个类之前先读完：</b>
 * <ol>
 *   <li>每个写 occupancy 的方法，第一句都是 {@code roomMapper.selectForUpdate(roomId)}；</li>
 *   <li>冲突检测必须用 {@code lockOverlapping}（当前读），<b>普通 SELECT 不算</b> ——
 *       房间行锁只保证写入互斥，不保证可见性。RR 下一致性读视图在事务内第一条普通 SELECT
 *       时就建立了，而真实入口在进内核之前还要先查房间/房型/民宿，快照建立点早于拿锁。
 *       这一点被 {@code OversellAuditIT} 实测过：改成当前读之前，真实入口 12 轮并发超卖 12 轮；</li>
 *   <li>状态流转用"带原状态的条件更新"当裁判，预检查只用来给错误信息。
 *       所有路径统一"先锁房间行、后更新订单行"，反过来会和回收任务形成 AB-BA 死锁。</li>
 * </ol>
 */
@Slf4j
@Service
public class BookingKernel {

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** 提前入住的上限：再早就不算"早到"，算误操作或需要改期。 */
    private static final Duration EARLY_CHECK_IN_LIMIT = Duration.ofHours(24);

    private static final DateTimeFormatter NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RoomMapper roomMapper;
    private final BookingMapper bookingMapper;
    private final OccupancyMapper occupancyMapper;

    public BookingKernel(RoomMapper roomMapper, BookingMapper bookingMapper, OccupancyMapper occupancyMapper) {
        this.roomMapper = roomMapper;
        this.bookingMapper = bookingMapper;
        this.occupancyMapper = occupancyMapper;
    }

    // ---------------------------------------------------------------- 下单

    /**
     * 下单结果。<b>要区分"新开的"与"幂等回放的"</b>：
     * 回放时调用方不能再写计费快照（那会拿现在的房型配置覆盖下单时冻结的值），
     * 也不能再记一条开单日志（同一张单出现两条 CREATE）。
     */
    public record CreateOutcome(Booking booking, boolean created) {
    }

    /** 只为测试与内部调用保留的简写；正式入口请用 {@link #createOrReplay}。 */
    @Transactional
    public Booking createBooking(CreateBookingCmd cmd) {
        return createOrReplay(cmd).booking();
    }

    /**
     * 幂等回放的<b>只读探测</b>，供服务层在跑"这间房现在还能不能卖"之前先问一次。
     *
     * <p>顺序很要紧。重试的场景是"第一次提交其实成功了、只是响应超时"，而前台点重试时
     * 距第一次已过去几分钟，其间房间可能被改成维修、房型可能调过 {@code minHours}。
     * 若先跑可售性预判，重试就会收到 2003/3004 —— 前台以为"这单没开出去"，
     * 可库里明明有一张，于是再开一张新的，房间被重复占用（审阅抓出）。
     * <b>已经在库里成功过的请求，答案只能是那张原单，不能是"现在卖不了"。</b>
     *
     * <p>三态而不是"命中/没命中"两态：同标识<b>不同</b>内容也是一次命中，只是答案不是原单而是
     * 拒绝。把它并进 MISS 让流程继续往下走，报错码就会被可售性判断抢掉 —— 前台改完时段重试时
     * 若新时段恰好不可售，收到的是 2003/3004 而不是 3005，于是看不出"上一次其实已经开出去了"。
     *
     * <p>用不加锁的读：这里发生在锁房间行之前，加锁会反掉"房间行 → 订单行"的统一顺序。
     * 探测漏了不影响正确性 —— {@link #createOrReplay} 锁后还会用当前读做权威判定。
     */
    public ReplayProbe replayOf(CreateBookingCmd cmd) {
        String requestId = blankToNull(cmd.requestId());
        if (requestId == null) {
            return new ReplayProbe(ReplayState.MISS, null);
        }
        Booking existing = bookingMapper.findByRequestId(requestId);
        if (existing == null) {
            return new ReplayProbe(ReplayState.MISS, null);
        }
        return new ReplayProbe(sameAsRequest(existing, cmd) ? ReplayState.REPLAY : ReplayState.CONFLICT,
                existing);
    }

    /** 幂等探测的三态。见 {@link #replayOf}。 */
    public enum ReplayState {
        /** 库里没有这个标识 —— 正常走下单。 */
        MISS,
        /** 同标识同内容 —— 答案就是那张原单。 */
        REPLAY,
        /** 同标识不同内容 —— 必须拒，且不能被后面的可售性判断抢掉错误码。 */
        CONFLICT
    }

    public record ReplayProbe(ReplayState state, Booking booking) {
    }

    /** 同标识不同内容的统一措辞：探测与服务层都用它，避免两处话术漂移。 */
    public static BizException idempotencyConflict(Booking existing) {
        return new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                "该请求已开出一张不同内容的订单（" + existing.getBookingNo() + "），请重新发起");
    }

    @Transactional
    public CreateOutcome createOrReplay(CreateBookingCmd cmd) {
        // 内核自己也要兜住非法区间 —— 它是最后一道防线，不能假设调用方校验过
        validateRange(cmd.startTime(), cmd.endTime());

        // 1. 锁住房间行 → 同房间下单天然串行。
        //    锁必须在这一步而不是回放之前：统一顺序是"房间行 → 订单行"，
        //    先锁订单行会和别人的下单/入住路径咬成死锁
        Room locked = roomMapper.selectForUpdate(cmd.roomId());
        if (locked == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + cmd.roomId());
        }

        // 2. 幂等回放：同一 requestId 重复提交返回原单。放在房间状态复核<b>之前</b> ——
        //    重试要拿回的是"当时那一次的结果"，而当时的房间是在售的；
        //    用现在的状态去否定一次已经成功的提交，只会把前台引向重复开单
        String requestId = blankToNull(cmd.requestId());
        if (requestId != null) {
            Booking existing = bookingMapper.findByRequestIdForUpdate(requestId);
            if (existing != null) {
                // 只认"同标识 + 同内容"。同标识不同内容不能回放 —— 典型场景是第一次提交其实
                // 成功了但响应超时，前台在同一个弹窗里改了时段重试；直接回放旧单会让前端
                // 把"新时段开单成功"报给用户，而库里存的是旧时段。
                if (sameAsRequest(existing, cmd)) {
                    log.info("幂等回放 requestId={} → booking {}", requestId, existing.getBookingNo());
                    return new CreateOutcome(existing, false);
                }
                throw idempotencyConflict(existing);
            }
        }

        // 3. 新单才复核房间状态。BookingService 在锁外也判过一次，但那是加锁前的快照：
        //    期间若有人把房间改成维修，只靠那次判断就会把房间卖掉、而且这张单永远办不了入住
        if (!Room.ON_SALE.equals(locked.getStatus())) {
            throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                    "房间 " + locked.getRoomNo() + " 当前状态 " + locked.getStatus() + "，不可售");
        }

        // 4. 冲突检测：当前读，打在含缓冲的 block 区间上
        LocalDateTime blockStart = cmd.startTime().minusMinutes(cmd.cleanBeforeMinutes());
        LocalDateTime blockEnd = cmd.endTime().plusMinutes(cmd.cleanAfterMinutes());
        requireNoConflict(cmd.roomId(), blockStart, blockEnd);

        // 5. 落库 booking + occupancy，同一事务
        Booking booking = new Booking();
        booking.setBookingNo(nextBookingNo());
        booking.setRequestId(requestId);
        booking.setSaleType(cmd.saleType() == null ? SaleType.HOURLY.name() : cmd.saleType());
        booking.setRoomId(cmd.roomId());
        booking.setGuestName(cmd.guestName());
        booking.setGuestPhone(cmd.guestPhone());
        booking.setStartTime(cmd.startTime());
        booking.setEndTime(cmd.endTime());
        booking.setStatus(cmd.status() == null ? Booking.CONFIRMED : cmd.status());
        booking.setHoldExpireAt(cmd.holdExpireAt());
        booking.setAmount(BigDecimal.ZERO);
        booking.setCreatedBy(AuthContext.currentUserId());
        bookingMapper.insert(booking);

        writeOccupancy(booking.getId(), cmd.roomId(), cmd.startTime(), cmd.endTime(), blockStart, blockEnd);
        log.info("下单成功 booking={} room={} [{}, {})", booking.getBookingNo(), cmd.roomId(),
                cmd.startTime(), cmd.endTime());
        return new CreateOutcome(booking, true);
    }

    // ------------------------------------------------- 改期 / 加时（最高危）

    /**
     * 改期。顺序：锁房间行 → 删本单旧占用 → 当前读重跑冲突检测 → 条件更新订单 → 插新占用。
     *
     * <p>"先删再查"是排除自己最省事也最不会漏的写法：删掉之后同事务内的查询看不到自己，
     * 不需要给冲突检测加一个 excludeBookingId 参数。检测失败抛异常会回滚那次删除，
     * 旧占用原样保留 —— {@code BookingFlowIT.changeTimesRollsBackOnConflict} 盯着这条。
     */
    @Transactional
    public Booking changeTimes(Long bookingId, LocalDateTime newStart, LocalDateTime newEnd,
                               int cleanBeforeMinutes, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        occupancyMapper.deleteByBookingId(bookingId);

        LocalDateTime blockStart = newStart.minusMinutes(cleanBeforeMinutes);
        LocalDateTime blockEnd = newEnd.plusMinutes(cleanAfterMinutes);
        requireNoConflict(booking.getRoomId(), blockStart, blockEnd);

        if (bookingMapper.rescheduleIfOpen(bookingId, newStart, newEnd) == 0) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE, "只有未入住的订单能改期");
        }
        writeOccupancy(bookingId, booking.getRoomId(), newStart, newEnd, blockStart, blockEnd);
        log.info("改期 booking={} → [{}, {})", booking.getBookingNo(), newStart, newEnd);
        return require(bookingId);
    }

    /**
     * 加时：只往后推 end。入住前缓冲已经用掉了，所以只重算退房后的缓冲。
     *
     * <p><b>起点必须沿用占用行现在的 {@code block_start}，不能用计划开始时间。</b>
     * 提前入住会把 block_start 往前扩（见 {@link #checkIn}），加时若拿计划起点重写整行，
     * 那段"实际已经住人"的时段就被抹平了 —— 于是能被下一单卖掉。
     * 这条是外部审阅指出、我核对后确认的真实缺陷。
     */
    @Transactional
    public Booking extend(Long bookingId, LocalDateTime newEnd, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        // 必须变长。这条在 W3 版本里有、重写时被我删掉了 —— 少了它，
        // "加时到 17:00"（原单 14:00–18:00）会把入住变成缩短。
        if (!newEnd.isAfter(booking.getEndTime())) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE,
                    "加时后的结束时间必须晚于当前结束时间 " + booking.getEndTime());
        }
        Occupancy current = occupancyMapper.findRoomBlock(bookingId);
        LocalDateTime blockStart = current == null ? booking.getStartTime() : current.getBlockStart();
        occupancyMapper.deleteByBookingId(bookingId);

        LocalDateTime blockEnd = newEnd.plusMinutes(cleanAfterMinutes);
        requireNoConflict(booking.getRoomId(), blockStart, blockEnd);

        // 把原 end 写进 where 当 CAS：两个加时请求先后执行时，后一个不能基于过期快照
        // 把前一个的结果改回去
        if (bookingMapper.extendIfInUse(bookingId, newEnd, booking.getEndTime()) == 0) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE, "订单状态或时段已变化，请刷新后重试");
        }
        writeOccupancy(bookingId, booking.getRoomId(), booking.getStartTime(), newEnd, blockStart, blockEnd);
        log.info("加时 booking={} → 至 {}", booking.getBookingNo(), newEnd);
        return require(bookingId);
    }

    /**
     * 加一晚：全日房整晚续住，把 end 往后推一天。
     *
     * <p><b>为什么不复用加时</b>：加时收的是"偏离模板终点那段时间"的超时费，用它续住会算成
     * "三晚房费 + 24 小时超时费" —— 按演示房型是 960 元，而正常一晚 388。客人说"再住一晚"，
     * 前台要的是多买一晚，不是多付 24 小时超时。
     *
     * <p><b>为什么不复用改期</b>：改期只在未入住时可用（{@code rescheduleIfOpen}），
     * 而加一晚最常见的场景恰恰是客人已经住进去了。
     *
     * <p>占用沿用"删自己 → 当前读检测 → 重写"，起点用占用行现在的 block_start ——
     * 提前入住会把起点前扩，拿计划起点重写会把那段抹平（与 {@link #extend} 同一条理由）。
     * {@code nights} 与计费快照由 service 侧重写：那是"按区间重算覆盖时长"的一整套纯函数，
     * 在这儿重写一遍必然分叉。
     */
    @Transactional
    public Booking addNight(Long bookingId, int cleanAfterMinutes,
                            LocalTime fullDayStart, LocalTime fullDayEnd) {
        Booking booking = require(bookingId);
        Room room = roomMapper.selectForUpdate(booking.getRoomId());
        // 锁内重读：状态与 end 都要用最新值，方法开头那个 booking 是加锁前的快照
        Booking fresh = bookingMapper.findByIdForUpdate(bookingId);
        if (fresh == null) {
            throw new BizException(ErrorCode.BOOKING_NOT_FOUND, "订单不存在: " + bookingId);
        }
        // 状态<b>先判</b>：状态不对就不该动占用。放在删占用之后判的话，错误码会被冲突检测抢走
        // （同一张已取消的单，有别的占用时回 3003、没有时回 3002），API 调用方没法稳定处理
        if (!Booking.CONFIRMED.equals(fresh.getStatus()) && !Booking.IN_USE.equals(fresh.getStatus())) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    "订单当前 " + fresh.getStatus() + "，只有已确认或已入住的单能加晚");
        }
        if (!SaleType.FULL_DAY.name().equals(fresh.getSaleType())) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "只有全日房能按整晚续住；钟点房请走加时");
        }
        // 与入住同一道闸门：房间已维修/下架还给它续一晚，续完也住不进去
        if (room != null && !Room.ON_SALE.equals(room.getStatus())) {
            throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                    "房间 " + room.getRoomNo() + " 当前状态 " + room.getStatus() + "，不能续住");
        }
        // 晚数从<b>区间</b>推，不用快照：先加时（比如延到 15:00）之后快照还是旧的 N，
        // 拿它当基准会把一张不落在整晚终点的单续成"另一张不落在整晚终点的单"
        int nights = SellPolicy.fullDayNights(fresh.getStartTime(), fresh.getEndTime(),
                fullDayStart, fullDayEnd);
        if (nights == 0) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE,
                    "当前离店时刻 " + fresh.getEndTime() + " 不是整晚终点（模板 "
                            + fullDayStart + " → " + fullDayEnd + "），没法按整晚续；"
                            + "这张单加时过，要继续住请退房后重开一张");
        }
        if (nights >= SellPolicy.MAX_FULL_DAY_NIGHTS) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE,
                    "一单最多 " + SellPolicy.MAX_FULL_DAY_NIGHTS + " 晚，再长请退房后重开");
        }
        LocalDateTime newEnd = fresh.getEndTime().plusDays(1);
        // 超时很久的在住单：end 已经是过去，再 +1 天可能还是过去 —— 那样加完还是"全是超时费"
        if (!newEnd.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE,
                    "这张单已超时，加一晚后离店仍是过去时刻（" + newEnd + "）；请先办退房再重开");
        }

        Occupancy current = occupancyMapper.findRoomBlock(bookingId);
        LocalDateTime blockStart = current == null ? fresh.getStartTime() : current.getBlockStart();
        occupancyMapper.deleteByBookingId(bookingId);
        LocalDateTime blockEnd = newEnd.plusMinutes(cleanAfterMinutes);
        requireNoConflict(fresh.getRoomId(), blockStart, blockEnd);

        // 状态与 expectedEnd 一起进 where：并发加晚时后一个不能覆盖前一个
        if (bookingMapper.addNightIfOpen(bookingId, newEnd, fresh.getEndTime()) == 0) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE, "订单状态或时段已变化，请刷新后重试");
        }
        writeOccupancy(bookingId, fresh.getRoomId(), fresh.getStartTime(), newEnd, blockStart, blockEnd);
        log.info("加一晚 booking={} → 至 {}", booking.getBookingNo(), newEnd);
        return require(bookingId);
    }

    // --------------------------------------------------------- 状态流转

    // 统一形状：锁房间行 → 条件更新（裁判）→ 需要时动 occupancy → 回读返回。

    @Transactional
    public Booking confirm(Long bookingId) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        if (bookingMapper.confirmIfHeld(bookingId) == 0) {
            throw illegal(booking.getStatus(), Booking.CONFIRMED);
        }
        return require(bookingId);
    }

    /**
     * 入住：<b>每次办理入住都要验一遍这间房现在到底能不能交出去</b>，再把占用块往前扩到实际入住时刻。
     *
     * <p>"能交出去"包含三件事，缺一不可：
     * <ol>
     *   <li><b>房间在售</b>——办入住期间可能被改成维修/下架；</li>
     *   <li><b>没有别人的占用</b>——上一位客人超时未退房、或房间还在等清扫；</li>
     *   <li><b>要扩出去的那一段也没人占</b>——计划 14:00 入住、客人 10:00 就到，
     *       10:00–14:00 也得是空的。</li>
     * </ol>
     *
     * <p>这三条曾经只做了第 3 条、而且只在"提前入住"时才跑。于是按时入住这条支路完全没设防：
     * A 单原定 12:00 退房却没走、B 单 14:05 按计划入住 —— 开新单时的闸门拦的是"新订单"，
     * 拦不住"已经存在的订单去兑付"，两个人就这么进了同一间房。
     * 修复的办法是统一成一条路径：<b>删掉自己的占用行 → 按 [min(实际, 计划起点), block_end) 检测 → 重写</b>，
     * 检测不过就抛异常、整笔随事务回滚（自己的占用行原样保留）。
     *
     * <p>"在住期间持续挡房"不在这里改 block_end，而是写在冲突查询的第二个条件里 ——
     * 占用表要始终满足"行不重叠"这个不变量。
     */
    @Transactional
    public Booking checkIn(Long bookingId, LocalDateTime actual) {
        Booking booking = require(bookingId);
        // 锁 + 读最新行一次完成：再单独 selectById 会命中一级缓存，读回加锁前的旧对象
        Room room = roomMapper.selectForUpdate(booking.getRoomId());
        if (room == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + booking.getRoomId());
        }
        if (!Room.ON_SALE.equals(room.getStatus())) {
            throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                    "房间 " + room.getRoomNo() + " 当前状态 " + room.getStatus() + "，不能办理入住");
        }
        // 同理：订单的 end_time 要读锁内最新值，否则并发改期后可能拿旧时段做判断
        Booking fresh = bookingMapper.findByIdForUpdate(bookingId);
        if (fresh == null) {
            throw new BizException(ErrorCode.BOOKING_NOT_FOUND, "订单不存在: " + bookingId);
        }

        LocalDateTime at = actual == null ? LocalDateTime.now() : actual;
        // 整段预订都已经过去就没得"入住"了：B 单 10:00–12:00、客人 14:00 才到，
        // 此刻房间里可能已经住着 13:00 开单的 A —— 只按 [10:00, 12:30) 检查是查不出人的。
        // 这条是外部审阅指出、我核对后确认的真实缺陷：闸门的位置对了，但窗口用的是历史值。
        // 提前入住要有上限：没有上限的话"给三个月后的单办入住"会把房间从现在起一路占住，
        // 而且这几乎必然是误操作。24 小时足够覆盖真实的"客人早到半天"场景
        if (at.isBefore(fresh.getStartTime().minus(EARLY_CHECK_IN_LIMIT))) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    "该预订 " + fresh.getStartTime() + " 才开始，提前入住不能超过 "
                            + EARLY_CHECK_IN_LIMIT.toHours() + " 小时；如需更早入住请改期");
        }
        if (!at.isBefore(fresh.getEndTime())) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    "该预订时段 " + fresh.getStartTime() + " → " + fresh.getEndTime()
                            + " 已经结束，无法直接入住；请改期或重新开单");
        }
        if (bookingMapper.checkInIfConfirmed(bookingId, at) == 0) {
            throw illegal(fresh.getStatus(), Booking.IN_USE);
        }

        // 区间一律用锁内读到的 fresh，不用方法开头的 booking（那是加锁前的快照）
        Occupancy blk = occupancyMapper.findRoomBlock(bookingId);
        LocalDateTime plannedStart = blk == null ? fresh.getStartTime() : blk.getBlockStart();
        LocalDateTime blockEnd = blk == null ? fresh.getEndTime() : blk.getBlockEnd();
        LocalDateTime effectiveStart = at.isBefore(plannedStart) ? at : plannedStart;

        // 先删自己再查 —— 排除自己最省事也最不会漏的写法（与改期同一套路）。
        // 检测不过会抛异常，这次删除随事务回滚，旧占用原样保留
        occupancyMapper.deleteByBookingId(bookingId);
        if (!occupancyMapper.lockOverlapping(fresh.getRoomId(), effectiveStart, blockEnd).isEmpty()) {
            throw new BizException(ErrorCode.ROOM_TIME_CONFLICT,
                    "这间房此刻交接不了：可能上一位客人还没退房，或房间还在等清扫。"
                            + "请先办退房 / 点「清扫完成」，再给客人办入住");
        }
        writeOccupancy(bookingId, fresh.getRoomId(), fresh.getStartTime(),
                fresh.getEndTime(), effectiveStart, blockEnd);
        if (at.isBefore(plannedStart)) {
            log.info("提前入住 booking={} 占用前扩至 {}", booking.getBookingNo(), at);
        }
        return require(bookingId);
    }

    /**
     * 退房结果。带着<b>实际写进去的</b>清扫区间，而不是调用方传进来的配置值。
     *
     * <p>为什么要专门回传：配置 30 分钟的清扫窗口会被下一单的开始时刻截断，挤到底时长度是 0。
     * 操作日志若照抄配置，就写下了一句没发生过的话 —— 而日志是审计凭证，
     * "日志说转清扫 30 分钟、日历上那一格却什么都没有"这种对不上，追查时比漏记更费时间。
     */
    public record CheckOutOutcome(Booking booking, LocalDateTime cleaningStart, LocalDateTime cleaningEnd) {

        /** 本次退房是否留下了待确认的清扫行。零长窗口同样算"留下了"（它是一件待办，不是一个时段）。 */
        public boolean needsCleaning() {
            return cleaningEnd != null;
        }

        /** 清扫窗口实际挡住了多少分钟。 */
        public long actualMinutes() {
            return cleaningEnd == null ? 0 : Duration.between(cleaningStart, cleaningEnd).toMinutes();
        }
    }

    /**
     * 退房。<b>不是删空，而是把 ROOM 行换成 CLEANING 行</b>：
     * {@code [实际退房时刻, +clean_after_minutes]}。
     *
     * <p>为什么：客人走了不等于房间能卖 —— 还没打扫。原先退房即删行，系统立刻显示可售，
     * 前台只能靠脑子记哪些房没打扫，"可售"这个信号因此不可信。
     * 现在保洁在前台点"清扫完成"（{@link #finishCleaning}）才真正释放。
     *
     * <p>缓冲时长仍来自房型配置，且 ROOM 行已删除，不存在 B1/B2 双算。
     */
    @Transactional
    public CheckOutOutcome checkOut(Long bookingId, LocalDateTime actual, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        LocalDateTime at = actual == null ? LocalDateTime.now() : actual;
        if (bookingMapper.checkOutIfInUse(bookingId, at) == 0) {
            throw illegal(booking.getStatus(), Booking.CHECKED_OUT);
        }
        occupancyMapper.deleteByBookingId(bookingId);
        LocalDateTime cleanEnd = null;
        if (cleanAfterMinutes > 0) {
            cleanEnd = at.plusMinutes(cleanAfterMinutes);
            // 清扫窗口不能压到下一单头上：晚退房把清扫挤进下一单时段时截断到下一段开始之前。
            // 不截断就会插出一对重叠的占用行，而"同房间行互不重叠"是冲突检测、日历、报表的共同前提
            LocalDateTime nextStart = occupancyMapper.findNextBlockStart(booking.getRoomId(), at);
            if (nextStart != null && nextStart.isBefore(cleanEnd)) {
                cleanEnd = nextStart;
            }
            if (!cleanEnd.isAfter(at)) {
                // 下一段占用已经开始了，清扫窗口被挤成零。这里**必须仍然留下一条 CLEANING 行**：
                // 清扫是"一件还没做完的事"，把行省掉等于同时省掉了三样东西 ——
                // 日历上的待清扫块、前台的"清扫完成"按钮、以及挡下一单入住的闸门
                // （客人还没走干净、房没打扫，下一单照样能办入住）。
                // 零长区间在几何上不占任何时段（半开区间里它是空集），所以不会破坏"行不重叠"；
                // 而冲突查询的 CLEANING 分支判的是 block_start，它照样从此刻起挡住后面的一切。
                cleanEnd = at;
                log.warn("退房晚于下一段占用的开始时刻，清扫窗口被挤成零 booking={} 房={} 下一段起于 {}",
                        booking.getBookingNo(), booking.getRoomId(), nextStart);
            }
            Occupancy cleaning = new Occupancy();
            cleaning.setRoomId(booking.getRoomId());
            cleaning.setBookingId(bookingId);
            cleaning.setStartTime(at);
            cleaning.setEndTime(cleanEnd);
            cleaning.setBlockStart(at);
            cleaning.setBlockEnd(cleanEnd);
            cleaning.setKind(Occupancy.KIND_CLEANING);
            occupancyMapper.insert(cleaning);
        }
        return new CheckOutOutcome(require(bookingId), cleanAfterMinutes > 0 ? at : null, cleanEnd);
    }

    /**
     * 清扫完成：释放 CLEANING 行，房间真正可售。
     *
     * <p>按房间而不是按订单收尾 —— 前台在房态图上点的是"302 号房打扫好了"，
     * 不关心那是上一位客人的哪张单。
     */
    @Transactional
    public int finishCleaning(Long roomId) {
        if (roomMapper.selectForUpdate(roomId) == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + roomId);
        }
        return occupancyMapper.deleteCleaningByRoom(roomId);
    }

    @Transactional
    public Booking cancel(Long bookingId) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        if (bookingMapper.cancelIfNotStarted(bookingId) == 0) {
            throw illegal(booking.getStatus(), Booking.CANCELLED);
        }
        occupancyMapper.deleteByBookingId(bookingId);
        return require(bookingId);
    }

    /**
     * 超时回收（实施计划 3.5）。
     *
     * <p>加锁顺序刻意和其它写路径一致：<b>先锁房间行，再条件更新订单行</b>。
     * 反过来的话，回收任务和前台的确认/取消就会形成 AB-BA 死锁 —— 而这个任务每 60 秒跑一次。
     * 候选已按 room_id 升序排出，多笔跨房处理时也不会互相咬。
     */
    @Transactional
    public int releaseExpiredHolds(LocalDateTime now) {
        int released = 0;
        for (HoldCandidate c : bookingMapper.findExpiredHolds(now)) {
            roomMapper.selectForUpdate(c.getRoomId());
            if (bookingMapper.releaseIfStillHeld(c.getBookingId(), now) == 1) {
                occupancyMapper.deleteByBookingId(c.getBookingId());
                released++;
            }
        }
        if (released > 0) {
            log.info("超时回收预占 {} 笔", released);
        }
        return released;
    }

    // ------------------------------------------------------------- 内部

    public Booking require(Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BizException(ErrorCode.BOOKING_NOT_FOUND, "订单不存在: " + bookingId);
        }
        return booking;
    }

    /** 冲突检测。当前读，且在房间行锁之后调用 —— 两个条件缺一不可。 */
    private void requireNoConflict(Long roomId, LocalDateTime blockStart, LocalDateTime blockEnd) {
        List<Long> hits = occupancyMapper.lockOverlapping(roomId, blockStart, blockEnd);
        if (!hits.isEmpty()) {
            throw new BizException(ErrorCode.ROOM_TIME_CONFLICT,
                    String.format("房间 %d 在 %s~%s 内已有占用（含清扫缓冲）", roomId, blockStart, blockEnd));
        }
    }

    private void writeOccupancy(Long bookingId, Long roomId, LocalDateTime start, LocalDateTime end,
                                LocalDateTime blockStart, LocalDateTime blockEnd) {
        Occupancy occ = new Occupancy();
        occ.setRoomId(roomId);
        occ.setBookingId(bookingId);
        occ.setStartTime(start);
        occ.setEndTime(end);
        occ.setBlockStart(blockStart);
        occ.setBlockEnd(blockEnd);
        occ.setKind(Occupancy.KIND_ROOM);
        occupancyMapper.insert(occ);
    }

    private BizException illegal(String from, String to) {
        return new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                BookingStateMachine.describe(from, to));
    }

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "end 必须严格晚于 start（半开区间 [start, end)）");
        }
    }

    /**
     * 幂等内容比对：房间、时段、客人、售卖方式都一致，才算同一个请求。
     *
     * <p><b>必须比 saleType。</b>它不只是个标签，它决定计费口径（钟点起步 3 小时 vs 全日模板 22 小时）。
     * 同一个 requestId 从 HOURLY 改成 FULL_DAY 重试时若回放旧单，前端会把"改了售卖方式成功"
     * 报给用户，而库里还是钟点 —— 与"同标识不同内容必须拒"的口径直接冲突。
     *
     * <p><b>刻意不比 status。</b>它不是不可变的创建参数：订单会合法地从 HELD 走到 SETTLED。
     * 把它算进"内容"，会让一次正常的重试在订单被确认之后突然变成"内容不同"。
     */
    private static boolean sameAsRequest(Booking existing, CreateBookingCmd cmd) {
        String saleType = cmd.saleType() == null ? SaleType.HOURLY.name() : cmd.saleType();
        return Objects.equals(existing.getRoomId(), cmd.roomId())
                && Objects.equals(existing.getStartTime(), cmd.startTime())
                && Objects.equals(existing.getEndTime(), cmd.endTime())
                && Objects.equals(existing.getGuestName(), cmd.guestName())
                && Objects.equals(existing.getSaleType(), saleType);
    }

    private String nextBookingNo() {
        return "B%s%03d%02d".formatted(LocalDateTime.now().format(NO_TS),
                SEQ.incrementAndGet() % 1000, ThreadLocalRandom.current().nextInt(100));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
