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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预订内核 —— 本项目唯一的正确性来源（实施计划第 3 节）。
 *
 * <p><b>铁律：任何写 occupancy 的路径，方法体第一句必须是 roomMapper.selectForUpdate(roomId)。</b>
 * 漏一条就可能在并发下超卖，而且只在并发下复现（实施计划 3.3 的三个前提）。
 *
 * <p>正确性论证：房间行锁把同一房间的写入串行化；事务在拿到锁之后才建立一致性读视图，
 * 因此任何先于锁提交的占用必然可见，任何锁之后的写入必然被挡住。
 */
@Slf4j
@Service
public class BookingKernel {

    /** booking_no 后缀：进程内自增 + 随机，单机前台够用。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final RoomMapper roomMapper;
    private final BookingMapper bookingMapper;
    private final OccupancyMapper occupancyMapper;

    public BookingKernel(RoomMapper roomMapper, BookingMapper bookingMapper, OccupancyMapper occupancyMapper) {
        this.roomMapper = roomMapper;
        this.bookingMapper = bookingMapper;
        this.occupancyMapper = occupancyMapper;
    }

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
        if (occupancyMapper.countOverlap(cmd.roomId(), blockStart, blockEnd) > 0) {
            throw new BizException(ErrorCode.ROOM_TIME_CONFLICT,
                    String.format("房间 %d 在 %s~%s 内已有占用（含清扫缓冲）",
                            cmd.roomId(), blockStart, blockEnd));
        }

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

        Occupancy occ = new Occupancy();
        occ.setRoomId(cmd.roomId());
        occ.setBookingId(booking.getId());
        occ.setStartTime(cmd.startTime());
        occ.setEndTime(cmd.endTime());
        occ.setBlockStart(blockStart);
        occ.setBlockEnd(blockEnd);
        occ.setKind(Occupancy.KIND_ROOM);
        occupancyMapper.insert(occ);

        log.info("下单成功 booking={} room={} [{}, {})", booking.getBookingNo(), cmd.roomId(),
                cmd.startTime(), cmd.endTime());
        return booking;
    }

    /**
     * 总纲 A 的取消/释放：先锁房间行，再删占用行，最后改订单状态，全程同一事务。
     * 顺序不能反 —— 先改状态再删行，中间插入的并发单会看到"状态已取消但行还在"。
     */
    @Transactional
    public void cancel(Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BizException(ErrorCode.BOOKING_NOT_FOUND, "订单不存在: " + bookingId);
        }
        if (roomMapper.selectForUpdate(booking.getRoomId()) == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + booking.getRoomId());
        }
        int deleted = occupancyMapper.deleteByBookingId(bookingId);
        booking.setStatus(Booking.CANCELLED);
        bookingMapper.updateById(booking);
        log.info("取消订单 booking={} 释放占用行 {} 条", bookingId, deleted);
    }

    /**
     * 超时回收（实施计划 3.5）：逐条条件更新，只有真的改走 HELD 的那一笔才删占用行。
     * 本方法自带事务，@Scheduled 入口必须调它而不是自己拼 SQL。
     */
    @Transactional
    public int releaseExpiredHolds(LocalDateTime now) {
        List<Long> ids = bookingMapper.findExpiredHeldIds(now);
        int released = 0;
        for (Long id : ids) {
            if (bookingMapper.releaseIfStillHeld(id, now) == 1) {
                Booking b = bookingMapper.selectById(id);
                if (b != null) {
                    roomMapper.selectForUpdate(b.getRoomId());
                }
                occupancyMapper.deleteByBookingId(id);
                released++;
            }
        }
        return released;
    }

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "end 必须严格晚于 start（半开区间 [start, end)）");
        }
    }

    private String nextBookingNo() {
        return "B%s%03d%02d".formatted(
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                SEQ.incrementAndGet() % 1000,
                ThreadLocalRandom.current().nextInt(100));
    }
}
