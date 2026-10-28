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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预订内核 —— 本项目唯一的正确性来源（实施计划第 3 节）。
 *
 * <p><b>铁律：本类里每一个写 occupancy 的方法，方法体第一句都是
 * {@code roomMapper.selectForUpdate(roomId)}。</b>漏一条就可能在并发下超卖，
 * 而且只在并发下复现（实施计划 3.3 的三个前提）。
 *
 * <p>正确性论证：房间行锁把同一房间的写入串行化；事务在拿到锁之后才建立一致性读视图，
 * 因此任何先于锁提交的占用必然可见，任何锁之后的写入必然被挡住。
 *
 * <p>本类只认"区间 + 缓冲分钟数"，不去查 room_type 表 —— 缓冲由调用方解析后传进来。
 * 这样 5.1 的边界用例可以用纯单测覆盖，不必为每个用例铺一套房源数据。
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
        validateRange(cmd.startTime(), cmd.endTime());

        // 1. 锁住房间行 → 同房间下单天然串行
        if (roomMapper.selectForUpdate(cmd.roomId()) == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + cmd.roomId());
        }

        // 2. 冲突检测打在含缓冲的 block 区间上
        LocalDateTime blockStart = cmd.startTime().minusMinutes(cmd.cleanBeforeMinutes());
        LocalDateTime blockEnd = cmd.endTime().plusMinutes(cmd.cleanAfterMinutes());
        requireNoOverlap(cmd.roomId(), blockStart, blockEnd);

        // 3. 落库 booking + occupancy，同一事务
        Booking booking = new Booking();
        booking.setBookingNo(nextBookingNo());
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
     * 改期。顺序照抄实施计划第 4 节末尾，别自由发挥：
     * 锁房间行 → 删本单旧占用 → 重跑冲突检测 → 插新占用，全程同一事务。
     *
     * <p>"先删再查"是排除自己最省事也最不会漏的写法：删掉之后同事务内的查询看不到自己，
     * 不需要给 existsOverlap 加一个 excludeBookingId 参数。
     * 而检测失败抛异常会回滚那次删除，旧占用原样保留 —— 这一点有测试盯着。
     */
    @Transactional
    public Booking changeTimes(Long bookingId, LocalDateTime newStart, LocalDateTime newEnd,
                              int cleanBeforeMinutes, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        if (!Booking.HELD.equals(booking.getStatus()) && !Booking.CONFIRMED.equals(booking.getStatus())) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    "只有预占(HELD)或已确认(CONFIRMED)的订单能改期，当前状态 " + booking.getStatus());
        }
        validateRange(newStart, newEnd);

        roomMapper.selectForUpdate(booking.getRoomId());
        occupancyMapper.deleteByBookingId(bookingId);

        LocalDateTime blockStart = newStart.minusMinutes(cleanBeforeMinutes);
        LocalDateTime blockEnd = newEnd.plusMinutes(cleanAfterMinutes);
        requireNoOverlap(booking.getRoomId(), blockStart, blockEnd);

        booking.setStartTime(newStart);
        booking.setEndTime(newEnd);
        bookingMapper.updateById(booking);
        writeOccupancy(bookingId, booking.getRoomId(), newStart, newEnd, blockStart, blockEnd);
        log.info("改期 booking={} → [{}, {})", booking.getBookingNo(), newStart, newEnd);
        return booking;
    }

    /** 加时：只往后推 end，同样要重跑冲突检测。已入住才允许加。 */
    @Transactional
    public Booking extend(Long bookingId, LocalDateTime newEnd, int cleanAfterMinutes) {
        Booking booking = require(bookingId);
        if (!Booking.IN_USE.equals(booking.getStatus())) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE, "只有在住订单可以加时");
        }
        if (!newEnd.isAfter(booking.getEndTime())) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "加时后的结束时间必须晚于当前结束时间");
        }

        roomMapper.selectForUpdate(booking.getRoomId());
        occupancyMapper.deleteByBookingId(bookingId);

        // 入住前缓冲已经用掉了，加时只需重新计算退房后的缓冲
        LocalDateTime blockStart = booking.getStartTime();
        LocalDateTime blockEnd = newEnd.plusMinutes(cleanAfterMinutes);
        requireNoOverlap(booking.getRoomId(), blockStart, blockEnd);

        booking.setEndTime(newEnd);
        bookingMapper.updateById(booking);
        writeOccupancy(bookingId, booking.getRoomId(), booking.getStartTime(), newEnd, blockStart, blockEnd);
        log.info("加时 booking={} → 至 {}", booking.getBookingNo(), newEnd);
        return booking;
    }

    // --------------------------------------------------------- 状态流转

    @Transactional
    public Booking confirm(Long bookingId) {
        Booking booking = require(bookingId);
        BookingStateMachine.assertCan(booking.getStatus(), Booking.CONFIRMED);
        roomMapper.selectForUpdate(booking.getRoomId());
        booking.setStatus(Booking.CONFIRMED);
        bookingMapper.updateById(booking);
        return booking;
    }

    @Transactional
    public Booking checkIn(Long bookingId, LocalDateTime actual) {
        Booking booking = require(bookingId);
        BookingStateMachine.assertCan(booking.getStatus(), Booking.IN_USE);
        roomMapper.selectForUpdate(booking.getRoomId());
        booking.setStatus(Booking.IN_USE);
        booking.setActualCheckIn(actual == null ? LocalDateTime.now() : actual);
        bookingMapper.updateById(booking);
        return booking;
    }

    /**
     * 退房即释放占用行（总纲 A）。
     *
     * <p>注意这里删掉的是<b>含未来缓冲的整块</b>：客人 16:00 实际退房，而原单是 14:00–18:00 + 45min，
     * 删行之后 16:00 起就可售了。这是有意为之 —— 缓冲保护的是"计划中的翻台"，人已经走了，
     * 决定权交回前台。要把清扫变成真正的闭环，做的是加分项 4（落 CLEANING 行 + 完成打点），
     * 而不是在这里保留一行假占用。
     */
    @Transactional
    public Booking checkOut(Long bookingId, LocalDateTime actual) {
        Booking booking = require(bookingId);
        BookingStateMachine.assertCan(booking.getStatus(), Booking.CHECKED_OUT);
        roomMapper.selectForUpdate(booking.getRoomId());
        int freed = occupancyMapper.deleteByBookingId(bookingId);
        booking.setStatus(Booking.CHECKED_OUT);
        booking.setActualCheckOut(actual == null ? LocalDateTime.now() : actual);
        bookingMapper.updateById(booking);
        log.info("退房 booking={} 释放占用行 {} 条", booking.getBookingNo(), freed);
        return booking;
    }

    /** 总纲 A 的取消：先锁房间行，再删占用行，最后改状态，全程同一事务。顺序不能反。 */
    @Transactional
    public Booking cancel(Long bookingId) {
        Booking booking = require(bookingId);
        BookingStateMachine.assertCan(booking.getStatus(), Booking.CANCELLED);
        roomMapper.selectForUpdate(booking.getRoomId());
        int deleted = occupancyMapper.deleteByBookingId(bookingId);
        booking.setStatus(Booking.CANCELLED);
        bookingMapper.updateById(booking);
        log.info("取消订单 booking={} 释放占用行 {} 条", booking.getBookingNo(), deleted);
        return booking;
    }

    /**
     * 超时回收（实施计划 3.5）：逐条条件更新，只有真的把 HELD 改走的那一笔才删占用行。
     *
     * <p>条件更新 + 影响行数判定是必须的：直接 UPDATE 再删行，会把"刚好在同一瞬间被前台
     * 确认"的订单释放掉。调用方（定时任务）必须通过本类的代理方法进来，不要自己拼 SQL。
     */
    @Transactional
    public int releaseExpiredHolds(LocalDateTime now) {
        List<Long> ids = bookingMapper.findExpiredHeldIds(now);
        int released = 0;
        for (Long id : ids) {
            if (bookingMapper.releaseIfStillHeld(id, now) == 1) {
                withRoomLockOf(id, roomId -> occupancyMapper.deleteByBookingId(id));
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

    /** 同一事务内按房间行锁执行一段写操作。 */
    private void withRoomLockOf(Long bookingId, Consumer<Long> action) {
        Booking b = bookingMapper.selectById(bookingId);
        if (b != null) {
            roomMapper.selectForUpdate(b.getRoomId());
            action.accept(b.getRoomId());
        }
    }

    private void requireNoOverlap(Long roomId, LocalDateTime blockStart, LocalDateTime blockEnd) {
        if (occupancyMapper.countOverlap(roomId, blockStart, blockEnd) > 0) {
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

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "end 必须严格晚于 start（半开区间 [start, end)）");
        }
    }

    private String nextBookingNo() {
        return "B%s%03d%02d".formatted(LocalDateTime.now().format(NO_TS),
                SEQ.incrementAndGet() % 1000, ThreadLocalRandom.current().nextInt(100));
    }
}
