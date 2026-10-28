package com.school.bnb.bench;

import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.service.BookingService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 防超卖的压测台 —— <b>打真实入口</b> {@code BookingService.create}，不是打内核。
 *
 * <p>这一点是整份压测数据的可信度来源：内核里 {@code FOR UPDATE} 恰好是事务的第一条语句，
 * 用它压测会把"RR 读视图建立在拿锁之前"这个真问题盖住（W3 那轮就是这么漏掉 12/12 超卖的）。
 * 真实入口在进内核之前还要查房间/房型/民宿，快照建立点早于拿锁 —— 压测必须走这条路。
 *
 * <p>为什么不装 JMeter：这个台子是纯 Java、跑 {@code mvn test} 就能复现的，
 * 能跟着 CI 一起跑，也不会因为"演示机上没装 JMeter"而拿不出数据。
 * JMeter 能做的事这里都做（定并发、定轮数、统计成功/拒绝/延迟分位），
 * 而且每一轮都能顺手断言"成功数不超过 1"。论文里写清用的是自研压测台 + 原因即可。
 */
@Slf4j
public final class ConcurrencyBench {

    private ConcurrencyBench() {
    }

    /**
     * 一轮 = 一批线程抢同一个时段。每轮换一间新房，避免"上一轮的占用还挡着这一轮"。
     *
     * @param roomPerRound 每轮用的房间（调用方准备好，数量 = rounds）
     */
    public record Plan(List<Long> roomPerRound, LocalDateTime start, LocalDateTime end,
                       int threads, int rounds) {
    }

    /**
     * @param oversoldRounds 出现"同一时段成功 >1 单"的轮数 —— 防超卖的全部意义就在这个数
     * @param conflicts      被正确拒绝的请求数
     * @param others         其它异常（死锁、超时等），非 0 说明系统在压力下不稳
     */
    /**
     * @param samples 延迟样本数 = 本轮所有请求数（成功 + 被拒 + 异常），不是"成功数"。
     *                报告里一并给出，读者才能判断 p50/p95 有多少依据。
     */
    public record Result(int threads, int rounds, int success, int conflicts, int others,
                         int oversoldRounds, int maxSuccessInOneRound,
                         int samples, long p50Millis, long p95Millis, long maxMillis,
                         long lockTimeMillis, long lockWaits,
                         List<Integer> successesPerRound) {
    }

    public static Result run(BookingService service, Plan plan) {
        return run(service, plan, null);
    }

    /**
     * @param jdbc 传进来就在压测前后各取一次 InnoDB 行锁统计，报告里多两列锁等待数据。
     *             "两种隔离级别各跑一次、比较锁等待"需要的就是这个数 —— 只看延迟会被
     *             连接池排队和 GC 噪声盖住，锁等待时间才是真的在量"锁"。
     */
    public static Result run(BookingService service, Plan plan, JdbcTemplate jdbc) {
        long[] lockBefore = lockSnapshot(jdbc);
        int threads = plan.threads();
        int rounds = plan.rounds();
        List<Integer> perRound = new ArrayList<>(rounds);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger others = new AtomicInteger();
        int oversoldRounds = 0;
        int maxInRound = 0;

        // 全部延迟样本先收着，最后统一算分位（比边跑边估稳）
        AtomicIntegerArray latencies = new AtomicIntegerArray(threads * rounds);
        AtomicInteger seq = new AtomicInteger();

        for (int r = 0; r < rounds; r++) {
            Long roomId = plan.roomPerRound().get(r);
            AtomicInteger roundSuccess = new AtomicInteger();
            // 用 CyclicBarrier 让所有线程尽量在同一瞬间开跑：没有它，线程是"接力"而不是"并发"，
            // 压力会被人为摊薄，测出来的超卖率为 0 只说明压测写得不够狠
            CyclicBarrier gate = new CyclicBarrier(threads);
            List<Thread> workers = new ArrayList<>(threads);

            for (int i = 0; i < threads; i++) {
                String requestId = "BENCH-" + r + "-" + i + "-" + System.nanoTime();
                Thread t = new Thread(() -> {
                    try {
                        gate.await();
                        long t0 = System.nanoTime();
                        try {
                            service.create(new CreateBookingReq(roomId, "压测客人", null,
                                    plan.start(), plan.end(), Booking.CONFIRMED, null, "HOURLY", requestId));
                            success.incrementAndGet();
                            roundSuccess.incrementAndGet();
                        } catch (BizException e) {
                            if (e.getCode() == ErrorCode.ROOM_TIME_CONFLICT) {
                                conflicts.incrementAndGet();
                            } else {
                                others.incrementAndGet();
                                log.warn("压测遇到非冲突业务异常: {}", e.getMessage());
                            }
                        } catch (Exception e) {
                            others.incrementAndGet();
                            log.warn("压测遇到异常: {}", e.toString());
                        }
                        // 延迟对**每一次**请求都记：只统计成功者会让样本从"每个请求"缩到"每轮一个"，
                        // 十轮就只有 10 个样本，还全是同一条成功路径，撑不起"延迟不随并发变化"这种话
                        latencies.set(seq.getAndIncrement(), (int) ((System.nanoTime() - t0) / 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.BrokenBarrierException e) {
                        // 屏障被破坏说明压测本身出了状况，必须让用例红，而不是记一条"其它异常"糊过去
                        throw new IllegalStateException("并发屏障被破坏，本轮压测作废", e);
                    }
                }, "bench-" + r + "-" + i);
                t.start();
                workers.add(t);
            }
            for (Thread t : workers) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            int got = roundSuccess.get();
            perRound.add(got);
            maxInRound = Math.max(maxInRound, got);
            if (got > 1) {
                oversoldRounds++;
            }
        }

        int[] samples = new int[seq.get()];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = latencies.get(i);
        }
        java.util.Arrays.sort(samples);

        long[] lockAfter = lockSnapshot(jdbc);
        return new Result(threads, rounds, success.get(), conflicts.get(), others.get(),
                oversoldRounds, maxInRound, samples.length,
                percentile(samples, 0.50), percentile(samples, 0.95),
                samples.length == 0 ? 0 : samples[samples.length - 1],
                lockAfter[0] - lockBefore[0], lockAfter[1] - lockBefore[1],
                perRound);
    }

    private static long percentile(int[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    /** 报告用的 markdown 表格行。 */
    public static String markdownRow(String label, Result r, int poolSize) {
        return "| %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d |".formatted(
                label, r.threads(), r.rounds(), poolSize,
                r.success(), r.conflicts(), r.others(), r.oversoldRounds(), r.samples(),
                r.p50Millis(), r.p95Millis(), r.lockWaits(), r.lockTimeMillis());
    }

    public static String markdownHeader() {
        return """
                | 场景 | 并发线程 | 轮数 | 连接池 | 成功 | 被拒 | 其它异常 | 超卖轮数 | 延迟样本 | p50(ms) | p95(ms) | 行锁等待次数 | 行锁等待(ms) |
                |---|---|---|---|---|---|---|---|---|---|---|---|---|""";
    }

    private static long[] lockSnapshot(JdbcTemplate jdbc) {
        if (jdbc == null) {
            return new long[]{0, 0};
        }
        Long ms = jdbc.queryForObject("""
                select variable_value from performance_schema.global_status
                where variable_name = 'Innodb_row_lock_time'
                """, Long.class);
        Long waits = jdbc.queryForObject("""
                select variable_value from performance_schema.global_status
                where variable_name = 'Innodb_row_lock_waits'
                """, Long.class);
        return new long[]{ms == null ? 0 : ms, waits == null ? 0 : waits};
    }
}
