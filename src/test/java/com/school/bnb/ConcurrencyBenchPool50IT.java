package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.bench.ConcurrencyBench;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Room;
import com.school.bnb.service.BookingService;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 与 {@link ConcurrencyBenchIT} 的 50 线程那一档成对照，唯一区别是<b>连接池 50 而不是 10</b>。
 *
 * <p>实施计划 7.4 那条坑：50 线程打 10 连接，测出来的 p95 里大头是排队等连接的时间，
 * 不是锁的开销。两行数字并排，就能看出"延迟不是被锁拖慢的"——
 * 这比单点给一个数可信，也多不了几行代码。
 *
 * <p>注意：连接池变大不等于更快。并发上限受制于数据库本身，连接多了只是少排队；
 * 论文里应当把两档都贴出来并说明这一点，而不是只挑好看的那个数。
 */
class ConcurrencyBenchPool50IT extends AbstractMysqlIT {

    private static final LocalDateTime SLOT_START = LocalDate.of(2026, 12, 3).atTime(10, 0);
    private static final LocalDateTime SLOT_END = LocalDate.of(2026, 12, 3).atTime(12, 0);

    @DynamicPropertySource
    static void biggerPool(DynamicPropertyRegistry registry) {
        // 后注册的同名属性覆盖 AbstractMysqlIT 里的 10（Spring 以最后一次 add 为准）
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
    }

    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("50 线程 + 连接池 50：零超卖，且延迟不再包含排队等连接的时间")
    void fiftyThreadsWithFiftyConnections() {
        // 与 ConcurrencyBenchIT 同样先热身一轮，两档的数字才可比
        ConcurrencyBench.run(bookingService,
                new ConcurrencyBench.Plan(freshRooms(1), SLOT_START, SLOT_END, 10, 1));

        ConcurrencyBench.Result r = ConcurrencyBench.run(bookingService,
                new ConcurrencyBench.Plan(freshRooms(10), SLOT_START, SLOT_END, 50, 10), jdbc);

        System.out.printf("[pool=50] 50 线程 × %d 轮：成功 %d / 被拒 %d / 超卖轮数 %d / p50 %dms / p95 %dms / 行锁等待 %d 次 %dms%n",
                r.rounds(), r.success(), r.conflicts(), r.oversoldRounds(), r.p50Millis(), r.p95Millis(),
                r.lockWaits(), r.lockTimeMillis());

        assertThat(r.oversoldRounds()).isZero();
        assertThat(r.others()).isZero();
        assertThat(r.success()).isEqualTo(r.rounds());
    }

    private List<Long> freshRooms(int count) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "池店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("池店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "池型" + tag, new BigDecimal("100.00"),
                1, new BigDecimal("20.00"), null, 0, 0, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();

        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(roomService.save(new RoomReq(null, pid, tid, "P" + tag + "-" + i, 1,
                    Room.ON_SALE)).getId());
        }
        return ids;
    }
}
