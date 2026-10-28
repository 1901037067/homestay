# 民宿房源管理与分时预订系统

本科毕业设计项目。前台单机使用，不做 C 端预订、不做支付、不做渠道同步、不做 RBAC。
设计与取舍的完整论证见 [毕设-民宿分时预订系统-实施计划.md](毕设-民宿分时预订系统-实施计划.md)。

## 技术栈

JDK 17 · Spring Boot 3.2.12 · MyBatis-Plus 3.5.9 · MySQL 8.0 · Flyway 9.22.3 ·
springdoc-openapi 2.3.0 · Vue 3 + Vite + Element Plus · JUnit 5 + Testcontainers

核心难点是**分时区间的防超卖**：MySQL 没有 Postgres 的 `EXCLUDE USING gist`，
区间不重叠靠应用层 `SELECT ... FOR UPDATE` + 半开区间校验保证 —— 这块的正确性论证、
并发测试和反向对照都在 `BookingKernelIT` 里。

## 目录

```
pom.xml                     单模块 Maven（不做多模块）
src/main/java/com/school/bnb
├── controller             认证 / 探针
├── service                Auth
├── mapper                 含手写的 selectForUpdate（MyBatis-Plus 不提供 FOR UPDATE）
├── entity                 6 张表
├── dto
├── booking                预订内核 BookingKernel —— 全项目唯一写 occupancy 的入口
└── common                 统一返回 / 异常 / session 拦截器
src/main/resources/db/migration  V1 结构、V2 结构性种子
src/test/java/com/school/bnb     FlywaySchemaIT / BookingKernelIT / SeedPasswordHashTest
db/demo/seed_demo.sql      演示流水（手动灌，不进 Flyway）
frontend/                  Vue 3 + Vite，dev proxy 打到 8080
scripts/                   env.sh / env.ps1 / reset-demo.sh
docker-compose.yml         开发期 MySQL
```

## 环境准备

需要 JDK 17、Maven 3.9、Docker Desktop（**引擎必须处于运行状态**，Testcontainers 和开发库都要它）。

系统级 `JAVA_HOME` 已指向 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`，
Maven 用 IDEA 内置的 3.9.11（不单独装），镜像与本地仓库由 `~/.m2/settings.xml` 决定。
新开终端本来就能直接 `mvn`；但 IDE/agent 内嵌的 Git Bash 可能**继承了失效的旧 `JAVA_HOME`**
（`C:\Program Files\Java\latest\jdk-26` 是个空壳目录，`java -version` 静默无输出，
极易误判成"没装 Java"）。这种情况下先加载项目自带的环境脚本：

```bash
source scripts/env.sh        # Git Bash
. .\scripts\env.ps1          # PowerShell
java -version && mvn -v
```

换机器时改 `scripts/env.*` 顶部路径，或用 `JAVA_HOME_OVERRIDE` / `MVN_HOME` 覆盖。

## 启动

```bash
docker compose up -d                  # MySQL 8.0，端口 3306，含 utf8mb4 与 +08:00 时区
bash scripts/reset-demo.sh            # 建库 → Flyway V1/V2 → 灌演示流水
mvn -B spring-boot:run                # 后端 8080，Swagger 在 /swagger-ui.html
cd frontend && npm install && npm run dev   # 前端 5173
```

浏览器打开 http://localhost:5173 ，账号 `admin` / `Admin@123`（`staff` 同密码，用于演示操作日志）。

前端不直连 8080，走 Vite dev proxy（`frontend/vite.config.js`），这样 `JSESSIONID`
是同源 cookie，后端不必开 CORS 白名单。

## 测试

```bash
mvn -B test
```

| 测试 | 证明什么 |
|---|---|
| `FlywaySchemaIT` | 迁移真的建出 6 张 InnoDB/utf8mb4 表；`uk_booking_kind` 确实是两列；6 个外键齐备；`idx_block` 列序正确；DATETIME 不被时区换算 |
| `BookingKernelIT` | 并发抢同一时段恰好一单成功；边界相接不算冲突；45 分钟清扫缓冲能挡住近邻单；跨零点全日房覆盖次日上午；取消即删占用行并可再售；**反向对照：不走行锁时数据库自己不拦两个重叠区间**；非法区间被拒 |
| `SeedPasswordHashTest` | V2 里的 admin 哈希能被 `BCryptPasswordEncoder` 验通（与登录校验同源） |

`*IT` 需要 Docker。surefire 已在 pom 里显式收 `**/*IT.java`，否则会出现"构建成功但一条集成测试没跑"的假绿。

改种子密码：`mvn -B test -Dtest=SeedPasswordHashTest`，读 `target/seed-admin-hash.txt`，粘回 V2。

## 已知约束

- 时间类型全链路 `LocalDateTime` / `datetime`，naive 本地时间。JDBC URL 里的
  `connectionTimeZone=SERVER&forceConnectionTimeZoneToSession=true&preserveInstants=false`
  是这套策略的落地条件，不要删。
- `occupancy` 有行即占位，释放即删除。因此**报表读 `booking`，不读 `occupancy`** ——
  历史订单在 occupancy 里没有行。
- 任何写 `occupancy` 的方法，第一句必须是 `roomMapper.selectForUpdate(roomId)`。
  漏一条就可能在并发下超卖，而且只在并发下复现。
- Windows 控制台是 cp936，`mvn` 的中文报错会乱码；需要读结果时落到文件再用编辑器打开。
