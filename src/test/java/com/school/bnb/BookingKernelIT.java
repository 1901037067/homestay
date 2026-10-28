package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.booking.BookingKernel;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Occupancy;
import com.school.bnb.entity.Property;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.mapper.OccupancyMapper;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 防超卖地基验证（实施计划第 3 节 + 5.1 边界用例）。
 *
 * <p>W1 就把并发跑起来，是为了在写业务之前就确认三件事：
 * ① {@code FOR UPDATE} 真的加锁（不是 MyBatis-Plus 给的假方法）；
 * ② 事务真的生效（不是被自调用吃掉了）；
 * ③ Testcontainers 起的 MySQL 支持并发连接。
 * 这三条任何一条在 W3 才发现，都要重来一遍。
 */
class BookingKernelIT extends AbstractMysqlIT {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 5, 0, 0);

    @Autowired BookingKernel kernel;
    @Autowired PropertyMapper propertyMapper;
    @Autowired RoomTypeMapper roomTypeMapper;
    @Autowired RoomMapper roomMapper;
    @Autowired BookingMapper bookingMapper;
    @Autowired OccupancyMapper occupancyMapper;
    @Autowired JdbcTemplate jdbc;

    /** 每个用例开一间新房，避免用例之间互相污染占用表。 */
    private long newRoom(int cleanBeforeMinutes, int cleanAfterMinutes) {
        Property p = new Property();
        p.setName("测试民宿-" + System.nanoTime());
        propertyMapper.insert(p);

        RoomType t = new RoomType();
        t.setPropertyId(p.getId());
        t.setName("大床房-" + System.nanoTime());
        t.setBasePrice(new BigDecimal("188.00"));
        t.setMinHours(3);
        t.setExtraHourPrice(new BigDecimal("30.00"));
        t.setCleanBeforeMinutes(cleanBeforeMinutes);
        t.setCleanAfterMinutes(cleanAfterMinutes);
        roomTypeMapper.insert(t);

        Room r = new Room();
        r.setPropertyId(p.getId());
        r.setRoomTypeId(t.getId());
        r.setRoomNo("A" + System.nanoTime());
        r.setStatus(Room.ON_SALE);
        roomMapper.insert(r);
        return r.getId();
    }

    private CreateBookingCmd cmd(long roomId, LocalDateTime start, LocalDateTime end,
                                 int cleanBefore, int cleanAfter) {
        return new CreateBookingCmd(roomId, "客人" + System.nanoTime(), null,
                start, end, cleanBefore, cleanAfter, Booking.CONFIRMED, null);
    }

    private int occupancyCount(long roomId) {
        Integer n = jdbc.queryForObject(
                "select count(1) from occupancy where room_id = ?", Integer.class, roomId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("并发抢同一时段：恰好一单成功，另一单被冲突检测拒绝")
    void concurrentSameSlot_exactlyOneWins() throws Exception {
        long roomId = newRoom(0, 0);
        LocalDateTime start = DAY.withHour(14).withMinute(0);
        LocalDateTime end = DAY.withHour(18).withMinute(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> {
                gate.await();
                try {
                    Booking b = kernel.createBooking(cmd(roomId, start, end, 0, 0));
                    return "OK:" + b.getBookingNo();
                } catch (BizException e) {
                    return "REJECT:" + e.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> t : tasks) {
            futures.add(pool.submit(t));
        }
        // 两个线程都已就位，同时放行 —— 这才叫并发，不是排队
        gate.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(s -> s.startsWith("OK")).hasSize(1);
        assertThat(results).filteredOn(s -> s.equals("REJECT:" + ErrorCode.ROOM_TIME_CONFLICT)).hasSize(1);
        assertThat(occupancyCount(roomId)).isEqualTo(1);
    }

    @Test
    @DisplayName("半开区间：退房 12:00 / 下一单入住 12:00 边界相接不算冲突")
    void adjacentBoundaryBothAllowed() {
        long roomId = newRoom(0, 0);
        kernel.createBooking(cmd(roomId, DAY.withHour(8), DAY.withHour(12), 0, 0));
        kernel.createBooking(cmd(roomId, DAY.withHour(12), DAY.withHour(16), 0, 0));

        assertThat(occupancyCount(roomId)).isEqualTo(2);
    }

    @Test
    @DisplayName("边界相接但被 45 分钟清扫缓冲挡住 —— 这是设计出来的正确行为")
    void bufferBlocksNearAdjacentSlot() {
        long roomId = newRoom(0, 45);
        kernel.createBooking(cmd(roomId, DAY.withHour(8), DAY.withHour(12), 0, 45));

        // 12:30 离退房只隔 30 分钟 < 45 分钟缓冲
        try {
            kernel.createBooking(cmd(roomId, DAY.withHour(12).withMinute(30), DAY.withHour(16), 0, 45));
            org.junit.jupiter.api.Assertions.fail("应当被清扫缓冲挡住");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
        }

        // 13:00 起才安全
        kernel.createBooking(cmd(roomId, DAY.withHour(13), DAY.withHour(16), 0, 45));
        assertThat(occupancyCount(roomId)).isEqualTo(2);
    }

    @Test
    @DisplayName("跨零点全日房：占用延伸到次日中午，次日早上不可再售")
    void crossMidnightFullDayBooking() {
        long roomId = newRoom(0, 0);
        kernel.createBooking(cmd(roomId, DAY.withHour(14), DAY.plusDays(1).withHour(12), 0, 0));

        try {
            kernel.createBooking(cmd(roomId, DAY.plusDays(1).withHour(9), DAY.plusDays(1).withHour(11), 0, 0));
            org.junit.jupiter.api.Assertions.fail("跨零点占用应覆盖次日上午");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
        }

        // 次日 12:00 起可再售（半开区间边界相接）
        kernel.createBooking(cmd(roomId, DAY.plusDays(1).withHour(12), DAY.plusDays(1).withHour(14), 0, 0));
        assertThat(occupancyCount(roomId)).isEqualTo(2);
    }

    @Test
    @DisplayName("总纲 A：取消即删占用行，房间立刻可售")
    void cancelFreesTheSlot() {
        long roomId = newRoom(0, 0);
        Booking b = kernel.createBooking(cmd(roomId, DAY.withHour(14), DAY.withHour(18), 0, 0));
        assertThat(occupancyCount(roomId)).isEqualTo(1);

        kernel.cancel(b.getId());
        assertThat(occupancyCount(roomId)).isZero();

        kernel.createBooking(cmd(roomId, DAY.withHour(14), DAY.withHour(18), 0, 0));
        assertThat(occupancyCount(roomId)).isEqualTo(1);
    }

    @Test
    @DisplayName("反向对照：不走行锁时，MySQL 自己不拦两个重叠区间")
    void controlGroupDatabaseAloneDoesNotPreventOversell() {
        long roomId = newRoom(0, 0);

        // 绕过 kernel，直接写两行重叠 occupancy —— 这就是"方案 A 必须靠应用层"的证据
        for (int i = 0; i < 2; i++) {
            Booking b = new Booking();
            b.setBookingNo("CTRL" + System.nanoTime() + i);
            b.setRoomId(roomId);
            b.setGuestName("对照客人" + i);
            b.setStartTime(DAY.withHour(14));
            b.setEndTime(DAY.withHour(18));
            b.setStatus(Booking.CONFIRMED);
            b.setAmount(BigDecimal.ZERO);
            bookingMapper.insert(b);

            Occupancy o = new Occupancy();
            o.setRoomId(roomId);
            o.setBookingId(b.getId());
            o.setStartTime(DAY.withHour(14));
            o.setEndTime(DAY.withHour(18));
            o.setBlockStart(DAY.withHour(14));
            o.setBlockEnd(DAY.withHour(18));
            o.setKind(Occupancy.KIND_ROOM);
            occupancyMapper.insert(o);
        }

        assertThat(occupancyCount(roomId)).isEqualTo(2);
        assertThat(occupancyMapper.countOverlap(roomId, DAY.withHour(14), DAY.withHour(18))).isEqualTo(2);
    }

    @Test
    @DisplayName("非法区间：end 不早于 start 一律拒绝")
    void rejectsInvertedRange() {
        long roomId = newRoom(0, 0);
        try {
            kernel.createBooking(cmd(roomId, DAY.withHour(18), DAY.withHour(18), 0, 0));
            org.junit.jupiter.api.Assertions.fail("等区间不是合法半开区间");
        } catch (BizException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE);
        }
        assertThat(occupancyCount(roomId)).isZero();
    }
}
