package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.service.BookingService;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 把连接池的隔离级别<b>强行改回 REPEATABLE READ</b>，再跑一遍真实入口并发。
 *
 * <p>目的只有一个：区分"当前读修复"和"隔离级别配置"各自的功劳。
 * 如果这个类也过，说明 {@code lockOverlapping} 的 {@code FOR UPDATE} 单独就够了，
 * READ COMMITTED 只是第二道防线，不是修复本身。
 * 属性集不同会让 Spring 起一个新的测试上下文，这里就是要那个不同。
 */
class OversellUnderRepeatableReadIT extends AbstractMysqlIT {

    @DynamicPropertySource
    static void forceRepeatableRead(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    }

    private static final LocalDateTime DAY = LocalDateTime.of(2027, 5, 1, 0, 0);
    private static final LocalDateTime S = DAY.withHour(14);
    private static final LocalDateTime E = DAY.withHour(18);

    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    private Room plainRoom() {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "RR店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("RR店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "RR型" + tag, new BigDecimal("100.00"),
                2, new BigDecimal("20.00"), null, 0, 0, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();
        return roomService.save(new RoomReq(null, pid, tid, "RR" + tag, 1, Room.ON_SALE));
    }

    private int occupancyRows(Long roomId) {
        Integer n = jdbc.queryForObject("select count(1) from occupancy where room_id = ?", Integer.class, roomId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("RR 下真实入口仍不超卖：证明是当前读的功劳，不是隔离级别配置")
    void realEntryHoldsUnderRepeatableRead() throws Exception {
        int rounds = 12;
        for (int i = 0; i < rounds; i++) {
            Room r = plainRoom();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch gate = new CountDownLatch(1);
            var task = (java.util.concurrent.Callable<String>) () -> {
                gate.await();
                try {
                    bookingService.create(new CreateBookingReq(
                            r.getId(), "RR并发客", null, S, E, Booking.CONFIRMED, null));
                    return "OK";
                } catch (Exception ex) {
                    return "REJECT";
                }
            };
            var f1 = pool.submit(task);
            var f2 = pool.submit(task);
            gate.countDown();
            String s1 = f1.get(60, TimeUnit.SECONDS);
            String s2 = f2.get(60, TimeUnit.SECONDS);
            pool.shutdownNow();

            assertThat(occupancyRows(r.getId()))
                    .as("第 %d 轮 [%s|%s]：RR 下也必须只有一行占用", i, s1, s2)
                    .isEqualTo(1);
        }
    }
}
