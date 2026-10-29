package com.school.bnb.booking;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预占超时回收。
 *
 * <p>两个容易踩的点，都写在实施计划 3.3：
 * ① {@code @Scheduled} 方法默认没有事务上下文，所以真正带 {@code @Transactional} 的逻辑
 * 放在内核里、由本类<b>跨 bean 调用</b>进去（同类内部调用会绕过 Spring 代理，事务静默失效）；
 * ② 回收必须走内核的条件更新 + 影响行数判定，不能"查出来直接删"。
 */
@Slf4j
@Component
// 能关掉是给测试用的：集成测试共享一个库、每个上下文都会起这个每 60 秒一次的后台任务，
// 而它会在断言之间悄悄释放预占单 —— 那是不必要的非确定性来源
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "bnb.hold.release-enabled", havingValue = "true", matchIfMissing = true)
public class HoldReleaseJob {

    private final BookingKernel kernel;

    public HoldReleaseJob(BookingKernel kernel) {
        this.kernel = kernel;
    }

    @Scheduled(fixedDelayString = "${bnb.hold.release-interval-ms:60000}")
    public void release() {
        int n = kernel.releaseExpiredHolds(LocalDateTime.now());
        if (n > 0) {
            log.info("预占超时回收 {} 笔", n);
        }
    }
}
