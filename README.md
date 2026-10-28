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
├── controller             认证 / 探针 / 民宿 / 房型 / 房间
├── service                Auth + 房源域三个 Service（含删除守卫）
├── mapper                 含手写的 selectForUpdate（MyBatis-Plus 不提供 FOR UPDATE）
├── entity                 6 张表
├── dto                    请求体（带校验注解）
├── vo                     列表返回体（带出上级名称，前端不必逐行再查）
├── config                 MyBatis-Plus 分页拦截器
├── booking                预订内核 BookingKernel —— 全项目唯一写 occupancy 的入口
└── common                 统一返回 / 异常 / session 拦截器
src/main/resources/db/migration  V1 结构、V2 结构性种子
src/test/java/com/school/bnb     FlywaySchemaIT / BookingKernelIT / RoomCatalogIT / SeedPasswordHashTest
db/demo/seed_demo.sql      演示流水（手动灌，不进 Flyway）
frontend/src
├── styles/tokens.css      Apple 设计令牌 + Element Plus 变量覆盖（明暗双模）
├── styles/base.css        排版、卡片、分段控件、状态胶囊
├── composables/useTheme.js浅色 / 深色 / 跟随系统，持久化在 localStorage
├── layout/AppShell.vue    毛玻璃侧栏 + 浮动标题栏
└── views                  登录 / 概览 / room 下三个管理页（路由级懒加载）
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
source scripts/env.sh                 # 只在"继承了失效 JAVA_HOME"的终端里需要
docker compose up -d                  # MySQL 8.0，端口 3306，含 utf8mb4 与 +08:00 时区
bash scripts/reset-demo.sh            # 建库 → Flyway V1/V2 → 灌演示流水
mvn -B spring-boot:run                # 后端 8080，Swagger 在 /swagger-ui.html
cd frontend && npm install && npm run dev   # 前端 5173
```

浏览器打开 http://localhost:5173 ，账号 `admin` / `Admin@123`（`staff` 同密码，用于演示操作日志）。

`reset-demo.sh` 会 drop database，**跑之前先停后端**。演示中途数据点乱了可以重跑一次，30 秒复原。

前端不直连 8080，走 Vite dev proxy（`frontend/vite.config.js`），这样 `JSESSIONID`
是同源 cookie，后端不必开 CORS 白名单。

## 界面与主题

按 Apple HIG 做了一套设计令牌（`frontend/src/styles/tokens.css`），通过 Element Plus 自己的
CSS 变量生效，没有覆盖 `.el-*` 类名，组件库升级不会崩。三个要点：

- **字体不加载 webfont。** Apple 自己就走系统栈：macOS/iOS 命中 SF Pro，Windows 命中 Segoe UI，
  中文回退 PingFang / 微软雅黑。零下载零闪烁，也避开国内 CDN 拉字体失败。
- **浅色模式的语义色分"图标用"和"文字用"两套。** `systemRed #FF3B30` 在白底上只有约 3.9:1，
  做正文不达标，所以文字取 Apple 的可达标变体 `#D70015`（绿 `#248A3D`、橙 `#A65E00` 同理）。
- **毛玻璃只给顶栏和浮层用**（`backdrop-filter: saturate(180%) blur(20px)`），卡片靠底色差分层，
  阴影极轻。全站到处半透明是"仿 Apple"最容易做错的地方。

浅色 / 深色 / 跟随系统三档在右上角分段控件，选择存 `localStorage`，`auto` 档跟随系统实时切换。

## 接口（当前）

```
POST /api/login          GET /api/logout        GET /api/me
GET  /api/ping           免鉴权探针，确认「应用 + Flyway + 库」三件事同时成立
GET  /api/properties     POST / PUT /{id} / DELETE /{id}
GET  /api/room-types     ?propertyId=           POST / PUT /{id} / DELETE /{id}
GET  /api/rooms          ?page&size&propertyId&roomTypeId&status&keyword
                         POST / PUT /{id} / PATCH /{id}/status / DELETE /{id}
POST /api/bookings                下单（status 只接受 HELD / CONFIRMED）
GET  /api/bookings                ?page&size&status&roomId&keyword&from&to
GET  /api/bookings/{id}
PUT  /api/bookings/{id}/times     改期：删旧占用→重跑冲突检测→插新占用，同一事务
POST /api/bookings/{id}/confirm | /cancel | /check-in | /check-out | /extend
GET  /api/rooms/availability      ?propertyId&start&end&minHours  → 可售房间
GET  /api/rooms/{id}/availability ?start&end&minHours             → 空闲区间列表
```

删除守卫（实施计划 2.6）：房间有历史订单 → `2002 ROOM_HAS_HISTORY`；民宿/房型有下级数据 →
`2013 HAS_CHILDREN`；同民宿下房号重复 → `2012 DUPLICATE_KEY`。下架走
`PATCH /api/rooms/{id}/status {"status":"OFFLINE"}`，不要试图删房间。

## 预订内核的三条规则

W3 落地时把计划里两处含糊的地方定成了明确规则，改代码前先看这段：

1. **可售窗口约束的是入住时刻**，不是结束时刻。全日房模板 14:00 入住、次日 12:00 离，
   天然落在 `08:00–22:00` 内，所以**不需要给它写"豁免"分支**（计划 2.5 原本设想要写一条）。
2. **起步时长只约束"同一天内结束"的请求。** 全日房必然跨天，因此自动不受 `min_hours` 限制。
   这两条都比"判断这个房型算不算全日房"要稳 —— 房型表里全日房和钟点房共用同一套时段字段。
3. **退房删掉的是含未来缓冲的整块。** 客人 16:00 实际退房，原单 14:00–18:00 + 45min 缓冲，
   删行之后 16:00 起就可售。缓冲保护的是"计划中的翻台"，人走了决定权交回前台；
   要把清扫做成真正的闭环，做的是加分项 4（落 `CLEANING` 行 + 完成打点），不是留一行假占用。

`/api/rooms/{id}/availability` **只做区间相减，不套可售窗口**：可售性是"这段能不能卖"的业务判断，
由 create 在锁外先拒、内核在锁内兜住；房态日历要画的是"这段时间是不是空的"。
两件事混在一个接口里，日历会出现说不清楚的洞。

预占超时回收由 `HoldReleaseJob` 每 60 秒跑一次（`bnb.hold.release-interval-ms` 可调）。
它调的是内核的 `@Transactional` 方法而不是自己写 SQL —— 同类内部调用会绕过 Spring 代理让事务静默失效。

## 测试

```bash
mvn -B test
```

| 测试 | 证明什么 |
|---|---|
| `FlywaySchemaIT` | 迁移真的建出 6 张 InnoDB/utf8mb4 表；`uk_booking_kind` 确实是两列；6 个外键齐备；`idx_block` 列序正确；DATETIME 不被时区换算 |
| `BookingKernelIT` | 并发抢同一时段恰好一单成功；边界相接不算冲突；45 分钟清扫缓冲能挡住近邻单；跨零点全日房覆盖次日上午；取消即删占用行并可再售；**反向对照：不走行锁时数据库自己不拦两个重叠区间**；非法区间被拒 |
| `RoomCatalogIT` | 民宿→房型→房间三级增改查且列表带出上级名称；房间有历史订单禁删（含"订单已取消但仍有历史"这一例）；有下级数据禁删民宿与房型；状态筛选、房号搜索、翻页不重不漏；房型不能挂到别的民宿下 |
| `BookingFlowIT` | 14 条：HELD→CONFIRMED→IN_USE→CHECKED_OUT 全流转且退房释放占用；非法跃迁被拒并提示可达状态；改期改写占用、撞车时**旧占用回滚**、改回原时段不被自己挡住；加时延长占用块 / 撞车被拒 / 不推出可售窗口；起步时长只约束当天、可售窗口只约束入住时刻；预占过期回收只动 HELD；空闲区间含缓冲相减；可售房间随取消而回归 |
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
- **MyBatis-Plus 3.5.9 起 `PaginationInnerInterceptor` 拆到了 `mybatis-plus-jsqlparser`**，
  只引 starter 会编译不过（报"找不到符号"，但符号确实在 extension 的包里）。别去翻 MP 的旧文档。
- **`@Container` 注解的 Testcontainers 容器会按测试类停止**，多个 IT 类共享时必须用
  静态块 `start()` 的 singleton 写法，否则第二个测试类拿到已关闭的连接。
- Windows 控制台是 cp936，`mvn` 的中文报错会乱码；需要读结果时落到文件再用编辑器打开。
  Git Bash 里含空格的 PATH 条目（`C:\Program Files\...`）不能让 `java` 直接执行，
  但 Maven 走 `JAVA_HOME` 是好的 —— `scripts/env.sh` 已用 `cygpath` 转成 POSIX 形式。
