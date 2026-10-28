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
 * RR 与 RC 的对照压测 —— 实施计划 W8 要求"两种隔离级别下各跑一次，比较吞吐与锁等待"。
 *
 * <p>这个类跑的是<b>正确代码</b>（当前读），只是把连接池的隔离级别从 READ COMMITTED 换成
 * REPEATABLE READ。与 `ConcurrencyBenchIT`（RC，10/20/50 三档）同一套压测台、同一台机器，
 * 两行数字并排就是"隔离级别的选择对这套实现意味着什么"的答案：
 * <ul>
 *   <li><b>正确性</b>：两边都必须零超卖。这条比什么都重要 —— 换隔离级别不能让正确性变差，
 *       否则"修复依赖 RC"的质疑就成立了（`OversellUnderRepeatableReadIT` 是它的 12 轮小样本版）。</li>
 *   <li><b>锁等待</b>：由压测台前后各取一次 {@code Innodb_row_lock_waits / _time} 统计得出。
 *       只比延迟是不可靠的（连接池排队与 GC 噪声会盖住锁的开销），锁等待时间才是真在量"锁"。</li>
 * </ul>
 *
 * <p>注意：全局状态的差值包含同库里其它测试的残留流量，所以只适合和同批次的其他行横向比较，
 * 不能当作绝对性能指标 —— 论文里要这么写。
 */
class ConcurrencyBenchRepeatableReadIT extends AbstractMysqlIT {

    private static final LocalDateTime SLOT_START = LocalDate.of(2026, 12, 4).atTime(10, 0);
    private static final LocalDateTime SLOT_END = LocalDate.of(2026, 12, 4).atTime(12, 0);

    @DynamicPropertySource
    static void repeatableRead(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.transaction-isolation",
                () -> "TRANSACTION_REPEATABLE_READ");
    }

    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("RR 下 50 线程 × 10 轮：仍然零超卖，并记录行锁等待")
    void repeatableReadAlsoNeverOversells() {
        // 与 ConcurrencyBenchIT 一致先热身一轮，数字才可比
        ConcurrencyBench.run(bookingService,
                new ConcurrencyBench.Plan(freshRooms(1), SLOT_START, SLOT_END, 10, 1));

        ConcurrencyBench.Result r = ConcurrencyBench.run(bookingService,
                new ConcurrencyBench.Plan(freshRooms(10), SLOT_START, SLOT_END, 50, 10), jdbc);

        System.out.printf("""
                [RR] 50 线程 × %d 轮：成功 %d / 被拒 %d / 超卖轮数 %d / p50 %dms / p95 %dms
                     行锁等待 %d 次、累计 %dms
                """, r.rounds(), r.success(), r.conflicts(), r.oversoldRounds(),
                r.p50Millis(), r.p95Millis(), r.lockWaits(), r.lockTimeMillis());

        assertThat(r.oversoldRounds()).as("换隔离级别不能让正确性变差").isZero();
        assertThat(r.others()).isZero();
        assertThat(r.success()).isEqualTo(r.rounds());
    }

    private List<Long> freshRooms(int count) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "RR店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("RR店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "RR型" + tag, new BigDecimal("100.00"),
                1, new BigDecimal("20.00"), null, 0, 0, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();

        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(roomService.save(new RoomReq(null, pid, tid, "RR" + tag + "-" + i, 1,
                    Room.ON_SALE)).getId());
        }
        return ids;
    }
}
