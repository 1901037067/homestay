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
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * W8 压测：<b>打真实入口</b>，三档并发，每档多轮，每轮 N 个线程抢同一间房的同一时段。
 *
 * <p>断言只有一条硬指标：<b>每轮"成功"不超过 1 单</b>。延迟分位数只记录不断言 ——
 * 它随机器、随 Docker 状态浮动，写进断言只会变成 flaky 测试；
 * 它的用途是给论文里的"延迟随并发上升"提供数据，不是门禁。
 *
 * <p>连接池用的是 {@code AbstractMysqlIT} 固定的 10：<b>这是有意的</b>，因为
 * 实施计划 7.4 那条坑正是指这个 —— 50 线程打 10 连接，测出来的是排队时间。
 * 报告里会把连接池写清楚，另有一档 pool=50 的对照组（{@code ConcurrencyBenchPool50IT}），
 * 两行数字并排就是"拐点"的证据。
 */
class ConcurrencyBenchIT extends AbstractMysqlIT {

    private static final LocalDateTime SLOT_START = LocalDate.of(2026, 12, 1).atTime(10, 0);
    private static final LocalDateTime SLOT_END = LocalDate.of(2026, 12, 1).atTime(12, 0);
    private static final int ROUNDS = 10;

    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("三档并发 × 10 轮：真实入口零超卖，被拒的都是 3003")
    void lockedEntryNeverOversells() throws IOException {
        // 先跑一轮热身并丢弃：第一轮要吃连接建立、InnoDB 缓冲池与执行计划缓存的冷启动，
        // 那段时间会被算进 p95 —— 不热身的话"10 线程比 50 线程还慢"，看着像结论其实只是冷启动
        ConcurrencyBench.run(bookingService,
                new ConcurrencyBench.Plan(freshRooms(1), SLOT_START, SLOT_END, 10, 1));

        List<ConcurrencyBench.Result> results = new ArrayList<>();
        for (int threads : new int[]{10, 20, 50}) {
            results.add(ConcurrencyBench.run(bookingService,
                    new ConcurrencyBench.Plan(freshRooms(ROUNDS), SLOT_START, SLOT_END, threads, ROUNDS), jdbc));
        }

        for (ConcurrencyBench.Result r : results) {
            assertThat(r.oversoldRounds()).as("%d 线程下不允许出现同一时段两张单", r.threads()).isZero();
            assertThat(r.others()).as("%d 线程下不应出现死锁/超时等非冲突异常", r.threads()).isZero();
            assertThat(r.success()).as("%d 线程 × %d 轮：每轮恰好一单", r.threads(), ROUNDS)
                    .isEqualTo(ROUNDS);
            assertThat(r.conflicts()).isEqualTo(r.threads() * r.rounds() - ROUNDS);
        }

        writeReport(results);
    }

    private void writeReport(List<ConcurrencyBench.Result> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 防超卖压测报告\n\n");
        sb.append("> 由 `ConcurrencyBenchIT` 自动生成，`mvn -Dtest=ConcurrencyBenchIT test` 可复现。\n")
                .append("> 打的是真实入口 `BookingService.create`（不是内核）：真实入口在拿房间锁之前\n")
                .append("> 还要查房间/房型/民宿，RR 读视图就建立在那一步 —— 这正是 W3 漏掉 12/12 超卖的成因。\n\n");
        sb.append(ConcurrencyBench.markdownHeader()).append('\n');
        for (ConcurrencyBench.Result r : results) {
            sb.append(ConcurrencyBench.markdownRow("有房间行锁 + 当前读", r, 10)).append('\n');
        }
        sb.append('\n');
        for (ConcurrencyBench.Result r : results) {
            sb.append("- %d 线程 / %d 轮：每轮抢同一间房同一时段，成功恒为 1，其余 %d 个请求全部 3003。\n"
                    .formatted(r.threads(), r.rounds(), r.conflicts()));
        }
        Path out = Path.of("target", "压测报告.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 每轮一间新房：复用同一间房会让"上一轮的占用还挡着这一轮"，把竞争条件洗掉。 */
    private List<Long> freshRooms(int count) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "压测店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("压测店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "压测型" + tag, new BigDecimal("100.00"),
                1, new BigDecimal("20.00"), null, 0, 0, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();

        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(roomService.save(new RoomReq(null, pid, tid, "B" + tag + "-" + i, 1,
                    Room.ON_SALE)).getId());
        }
        return ids;
    }
}
