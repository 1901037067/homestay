package com.school.bnb.booking;

import com.school.bnb.common.AuthContext;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Occupancy;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.mapper.OccupancyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.vo.HoldCandidate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
                log.info("幂等回放 requestId={} → booking {}", cmd.requestId(), existing.getBookingNo());
                return existing;
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

    /** 加时：只往后推 end。入住前缓冲已经用掉了，所以只重算退房后的缓冲。 */
    @Transactional
    public Booking extend(Long bookingId, LocalDateTime newEnd, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        occupancyMapper.deleteByBookingId(bookingId);

        LocalDateTime blockStart = booking.getStartTime();
        LocalDateTime blockEnd = newEnd.plusMinutes(cleanAfterMinutes);
        requireNoConflict(booking.getRoomId(), blockStart, blockEnd);

        if (bookingMapper.extendIfInUse(bookingId, newEnd) == 0) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE, "只有在住订单可以加时");
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

    @Transactional
    public Booking checkIn(Long bookingId, LocalDateTime actual) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        if (bookingMapper.checkInIfConfirmed(bookingId, actual == null ? LocalDateTime.now() : actual) == 0) {
            throw illegal(booking.getStatus(), Booking.IN_USE);
        }
        return require(bookingId);
    }

    /**
     * 退房即释放占用行（总纲 A）。
     *
     * <p>删掉的是<b>含未来缓冲的整块</b>：客人 16:00 实际退房，而原单是 14:00–18:00 + 45min，
     * 删行之后 16:00 起就可售。缓冲保护的是"计划中的翻台"，人已经走了，决定权交回前台。
     * 要把清扫做成真正的闭环，做的是加分项 4（落 CLEANING 行 + 完成打点），
     * 而不是在这里保留一行假占用。这条取舍要在前台界面上说清楚。
     */
    @Transactional
    public Booking checkOut(Long bookingId, LocalDateTime actual) {
        Booking booking = require(bookingId);
        roomMapper.selectForUpdate(booking.getRoomId());
        if (bookingMapper.checkOutIfInUse(bookingId, actual == null ? LocalDateTime.now() : actual) == 0) {
            throw illegal(booking.getStatus(), Booking.CHECKED_OUT);
        }
        occupancyMapper.deleteByBookingId(bookingId);
        return require(bookingId);
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

    private String nextBookingNo() {
        return "B%s%03d%02d".formatted(LocalDateTime.now().format(NO_TS),
                SEQ.incrementAndGet() % 1000, ThreadLocalRandom.current().nextInt(100));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
