package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.school.bnb.bench.ConcurrencyBench;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Room;
import com.school.bnb.mapper.OccupancyMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>反向对照实验</b> —— 整份 W8 素材里最值钱的一条。
 *
 * <p>正向压测只能说明"现在没超卖"，不能说明<b>是那把锁起的作用</b>。这个类把冲突检测
 * 从当前读（{@code FOR UPDATE}）换回普通 SELECT，其它一个字不动（房间行锁照旧），
 * 再把隔离级别强行改回 RR，然后跑同一套压测 —— 于是"同一时段成功 2 单"真的会发生。
 *
 * <p>三个细节解释了为什么这样才是公平的对照：
 * <ol>
 *   <li><b>房间行锁故意留着。</b>它给的是互斥，不是可见性；RR 下事务的读视图在第一条普通
 *       SELECT 时就建立了（真实入口要查房间/房型/民宿），所以拿着锁也读到旧快照。
 *       这一条把"锁 = 正确"的直觉直接打掉，正是论文第 4 章要讲的东西。</li>
 *   <li><b>隔离级别强行拉回 RR。</b>应用跑在 RC 上时每条语句各取新快照，超卖窗口会小很多，
 *       对照会变得不稳定。RR 只是让这个 bug <b>稳定显形</b>，不是它的成因 ——
 *       成因是"用普通读做判定"。</li>
 *   <li><b>走真实入口。</b>内核里 {@code FOR UPDATE} 恰好是第一条语句，用内核压测看不出问题。</li>
 * </ol>
 *
 * <p>这条测试断言的是"<b>必须</b>出现超卖"：如果哪天它变绿在没有超卖，说明对照失效了
 * （比如某一层又加了串行化），那时候正向压测的"零超卖"也就不再能证明任何事。
 */
class OversellWithoutLockIT extends AbstractMysqlIT {

    private static final LocalDateTime SLOT_START = LocalDate.of(2026, 12, 2).atTime(10, 0);
    private static final LocalDateTime SLOT_END = LocalDate.of(2026, 12, 2).atTime(12, 0);
    private static final int THREADS = 10;
    private static final int ROUNDS = 10;

    /** 让一致性读的旧快照能够形成：只有 RR 才有"事务内固定读视图"。 */
    @DynamicPropertySource
    static void repeatableRead(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.transaction-isolation",
                () -> "TRANSACTION_REPEATABLE_READ");
    }

    @SpyBean OccupancyMapper occupancyMapper;
    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;

    @BeforeEach
    void downgradeConflictCheckToPlainRead() {
        // 把"当前读"退回普通读：同样的重叠判定，只是不再加锁、不再读最新已提交版本。
        // 这就是修复前那句 countOverlap(...) > 0 的行为。
        doAnswer(inv -> {
            Long roomId = inv.getArgument(0);
            LocalDateTime start = inv.getArgument(1);
            LocalDateTime end = inv.getArgument(2);
            return occupancyMapper.countOverlap(roomId, start, end) > 0 ? List.of(-1L) : List.of();
        }).when(occupancyMapper).lockOverlapping(any(), any(), any());
    }

    @Test
    @DisplayName("去掉当前读（房间锁仍在）：同一时段会出现两张单 —— 证明锁是承重的")
    void removingTheCurrentReadOversells() {
        ConcurrencyBench.Result r = ConcurrencyBench.run(bookingService,
                new ConcurrencyBench.Plan(freshRooms(ROUNDS), SLOT_START, SLOT_END, THREADS, ROUNDS));

        System.out.printf("""
                [反向对照] %d 线程 × %d 轮，房间行锁保留、冲突检测换回普通读、隔离级别 RR
                  成功 %d 单（正常应为 %d）/ 被拒 %d / 超卖轮数 %d / 单轮最多成功 %d 单
                """, THREADS, ROUNDS, r.success(), ROUNDS, r.conflicts(),
                r.oversoldRounds(), r.maxSuccessInOneRound());

        assertThat(r.success()).as("成功数超过轮数 = 出现了会重复卖房的单")
                .isGreaterThan(ROUNDS);
        assertThat(r.oversoldRounds()).as("至少有一轮出现了同一时段两张单").isPositive();
        assertThat(r.maxSuccessInOneRound()).as("单轮超卖的数量").isGreaterThan(1);
    }

    private List<Long> freshRooms(int count) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "对照店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("对照店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "对照型" + tag, new BigDecimal("100.00"),
                1, new BigDecimal("20.00"), null, 0, 0, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();

        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(roomService.save(new RoomReq(null, pid, tid, "C" + tag + "-" + i, 1,
                    Room.ON_SALE)).getId());
        }
        return ids;
    }
}
