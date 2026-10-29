package com.school.bnb;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试基座：<b>打真 MySQL</b>，不嵌 H2。
 *
 * <p>本项目的核心正确性（{@code FOR UPDATE}、半开区间重叠、utf8mb4、外键拒删）全是
 * MySQL 方言行为，H2 一条都测不出来。
 *
 * <p><b>为什么不用 {@code @Container} 注解：</b>Testcontainers 的 JUnit5 扩展会按<em>测试类</em>
 * 管理带 {@code @Container} 的静态字段的生命周期 —— 第一个类跑完就把容器停掉，第二个类拿到的是
 * 已关闭连接的 URL（症状：Hikari "No operations allowed after connection closed"，全绿变全红）。
 * 所以这里用官方推荐的 singleton container 写法：静态块里 start()，进程结束由 Ryuk 回收。
 */
@SpringBootTest(properties = "bnb.hold.release-enabled=false")
abstract class AbstractMysqlIT {

    static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
                .withCommand("--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--default-time-zone=+08:00");
        MYSQL.start();
    }

    /** 与 application.yml 保持同一组时区参数，否则测试环境和开发环境的 DATETIME 语义会分叉。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String base = MYSQL.getJdbcUrl();
        // 别硬拼 "&"：Testcontainers 返回的 URL 不带查询串，用 & 起头会让驱动把
        // "test&connectionTimeZone=..." 整段当成库名（报 Identifier name too long）。
        String url = base + (base.contains("?") ? "&" : "?")
                + "connectionTimeZone=SERVER&forceConnectionTimeZoneToSession=true&preserveInstants=false";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // 并发测试至少需要两条连接，别让连接池把并发变成排队
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }
}
