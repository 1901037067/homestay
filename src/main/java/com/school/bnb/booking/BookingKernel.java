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
import java.time.LocalDateTime;
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

    @Transactional
    public Booking createBooking(CreateBookingCmd cmd) {
        // 内核自己也要兜住非法区间 —— 它是最后一道防线，不能假设调用方校验过
        validateRange(cmd.startTime(), cmd.endTime());

        // 1. 锁住房间行 → 同房间下单天然串行
        if (roomMapper.selectForUpdate(cmd.roomId()) == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + cmd.roomId());
        }

        // 2. 幂等回放：同一 requestId 重复提交返回原单。放在锁之后，同房间的并发双击必然串行。
        if (cmd.requestId() != null && !cmd.requestId().isBlank()) {
            Booking existing = bookingMapper.findByRequestIdForUpdate(cmd.requestId());
            if (existing != null) {
                // 只认"同标识 + 同内容"。同标识不同内容不能回放 —— 典型场景是第一次提交其实
                // 成功了但响应超时，前台在同一个弹窗里改了时段重试；直接回放旧单会让前端
                // 把"新时段开单成功"报给用户，而库里存的是旧时段。
                if (sameAsRequest(existing, cmd)) {
                    log.info("幂等回放 requestId={} → booking {}", cmd.requestId(), existing.getBookingNo());
                    return existing;
                }
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "该请求已开出一张不同内容的订单（" + existing.getBookingNo() + "），请重新发起");
            }
        }

        // 3. 冲突检测：当前读，打在含缓冲的 block 区间上
        LocalDateTime blockStart = cmd.startTime().minusMinutes(cmd.cleanBeforeMinutes());
        LocalDateTime blockEnd = cmd.endTime().plusMinutes(cmd.cleanAfterMinutes());
        requireNoConflict(cmd.roomId(), blockStart, blockEnd);

        // 4. 落库 booking + occupancy，同一事务
        Booking booking = new Booking();
        booking.setBookingNo(nextBookingNo());
        booking.setRequestId(blankToNull(cmd.requestId()));
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
        return booking;
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
    public Booking checkOut(Long bookingId, LocalDateTime actual, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        LocalDateTime at = actual == null ? LocalDateTime.now() : actual;
        if (bookingMapper.checkOutIfInUse(bookingId, at) == 0) {
            throw illegal(booking.getStatus(), Booking.CHECKED_OUT);
        }
        occupancyMapper.deleteByBookingId(bookingId);
        if (cleanAfterMinutes > 0) {
            Occupancy cleaning = new Occupancy();
            cleaning.setRoomId(booking.getRoomId());
            cleaning.setBookingId(bookingId);
            cleaning.setStartTime(at);
            cleaning.setEndTime(at.plusMinutes(cleanAfterMinutes));
            cleaning.setBlockStart(at);
            cleaning.setBlockEnd(at.plusMinutes(cleanAfterMinutes));
            cleaning.setKind(Occupancy.KIND_CLEANING);
            occupancyMapper.insert(cleaning);
        }
        return require(bookingId);
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
