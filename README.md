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
├── controller             认证 / 探针 / 民宿 / 房型 / 房间 / 预订 / 日历 / 账单 / 价格规则 / 日志
├── service                房源域 + Booking（策略与装配）+ Calendar + Billing + RateRule + RoomContext
├── mapper                 含手写的 selectForUpdate 与 lockOverlapping（MyBatis-Plus 不提供 FOR UPDATE）
├── entity                 8 张表
├── dto                    请求体（带校验注解）
├── vo                     列表返回体（带出上级名称，前端不必逐行再查）
├── config                 MyBatis-Plus 分页拦截器
├── booking                预订内核 BookingKernel —— 全项目唯一写 occupancy 的入口；
│                          SellPolicy（可售性唯一出处）/ SaleType / BookingStateMachine
├── billing                PriceCalculator —— 纯函数，不碰 DB 也不读时钟；RateRuleDef
└── common                 统一返回 / 异常 / session 拦截器 / AuthContext
src/main/resources/db/migration  V1 结构、V2 结构性种子、V3 幂等与售卖方式、V4 操作日志、V5 计费
src/test/java/com/school/bnb     防超卖三件套 + 流转/日历/审计/计费 IT；billing/PriceCalculatorTest 纯单测
db/demo/seed_demo.sql      演示流水（手动灌，不进 Flyway）
frontend/src
├── styles/tokens.css      Apple 设计令牌 + Element Plus 变量覆盖（明暗双模）
├── styles/base.css        排版、卡片、分段控件、状态胶囊
├── composables/useTheme.js浅色 / 深色 / 跟随系统，持久化在 localStorage
├── layout/AppShell.vue    毛玻璃侧栏 + 浮动标题栏
├── components/BillDialog.vue  账单弹窗（日历与订单列表共用，试算/快照同一套渲染）
├── utils/dtime.js         naive 本地时间的解析与格式化（不引 dayjs，避免偏移被算错）
└── views                  登录 / 概览 / calendar 房态 / booking 订单 / room 三个管理页 /
                           billing 价格规则 / system 操作日志（全部路由级懒加载）
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

七个页面：概览 / 房态日历 / 订单 / 房间 / 房型 / 民宿 / 价格规则 / 操作日志。
两条刻意的设计：

- **房态日历是主操作台**（点空白格开单、点块做入住/退房/加时/结算/清扫确认），
  因为前台的工作单位是"这间房这段时间归谁"，不是"这张订单"。
- **「订单」页不是日历的重复**：退房后 ROOM 占用行被换成 CLEANING 行，等保洁点完、
  或者客人第二天早上才来结账，日历上已经找不到那张单了。结算入口必须有第二个家。

## 接口（当前）

```
POST /api/login          GET /api/logout        GET /api/me
GET  /api/ping           免鉴权探针，确认「应用 + Flyway + 库」三件事同时成立
GET  /api/properties     POST / PUT /{id} / DELETE /{id}
GET  /api/room-types     ?propertyId=           POST / PUT /{id} / DELETE /{id}
GET  /api/rooms          ?page&size&propertyId&roomTypeId&status&keyword
                         POST / PUT /{id} / PATCH /{id}/status / DELETE /{id}
POST /api/bookings                下单（status 只接受 HELD / CONFIRMED；带 requestId 幂等）
GET  /api/bookings                ?page&size&status&roomId&keyword&from&to
GET  /api/bookings/{id}
PUT  /api/bookings/{id}/times     改期：删旧占用→重跑冲突检测→插新占用，同一事务
POST /api/bookings/{id}/confirm | /cancel | /check-in | /check-out | /extend
POST /api/rooms/{id}/cleaning-done  保洁完成，释放清扫占用
GET  /api/rooms/availability      ?propertyId&start&end&minHours&saleType → 可售房间
GET  /api/rooms/{id}/availability ?start&end&minHours             → 空闲区间列表
GET  /api/calendar                ?propertyId&date                → 房态日历数据源
GET  /api/bookings/{id}/bill      已结算返账单快照，未结算返实时试算（snapshot 字段区分）
POST /api/bookings/{id}/bill/preview  按当前规则重算，不写库
POST /api/bookings/{id}/settle        收款：落 bill 快照 + CHECKED_OUT→SETTLED
GET  /api/rate-rules             ?propertyId=  POST / PUT /{id} / DELETE /{id}  价格规则
GET  /api/operation-logs          ?page&size&action&entity&entityId&username
```

`/api/calendar` 的 `propertyId` 是必填：前台一次只看一家店，而营业日切割点是按店配置的，
两家店混在一张图上窗口对不齐。

删除守卫（实施计划 2.6）：房间有历史订单 → `2002 ROOM_HAS_HISTORY`；民宿/房型有下级数据 →
`2013 HAS_CHILDREN`；同民宿下房号重复 → `2012 DUPLICATE_KEY`。下架走
`PATCH /api/rooms/{id}/status {"status":"OFFLINE"}`，不要试图删房间。

## 预订内核的四条规则

W3 落地、W4 之后被一轮审阅修正。改这块代码前先看这段：

1. **冲突检测必须是当前读。** 房间行锁只解决**互斥**，不解决**可见性**。
   REPEATABLE READ 下一致性读视图在事务内**第一条普通 SELECT** 时就建立了，
   而真实入口 `BookingService.create` 在拿房间锁之前还要先查房间/房型/民宿（取缓冲与起步时长）——
   快照建立点早于拿锁，锁后的普通 SELECT 看不见别人刚提交的占用。
   实测两线程抢同一时段 12 轮，**超卖 12 轮**。现在检测走 `lockOverlapping`（`FOR UPDATE`）；
   `countOverlap` 保留但只给报表和调试用，注释里写死了不许拿它做判定。
2. **加锁方向全局一致：先房间行锁，后订单行条件更新；多房间按 `room_id` 升序。**
   超时回收原先是反的（先更新订单再锁房间），而它每 60 秒跑一次 —— 与前台确认正好构成 AB-BA 死锁。
3. **状态流转用"带原状态的条件更新"当裁判。** 预检查可能基于过期快照，
   只有 `update ... where id = ? and status = ?` 的影响行数可信。
4. **可售窗口约束入住时刻；售卖方式显式声明。** `saleType = HOURLY | FULL_DAY`：
   钟点受 `min_hours` 约束且不许跨天；全日房区间必须与服务端按房型算出的模板完全一致。
   原先用"是否跨天"推断是不是全日房，逆命题不成立 —— 22:00→次日 00:30 只有 2.5 小时，
   却因为跨天绕过了起步时长校验。

`/api/rooms/{id}/availability` **只做区间相减，不套可售策略**：日历要画"这段时间是不是空的"，
可售性是"这段能不能卖"，混在一个接口里日历会出现说不清楚的洞。可售判断统一走 `SellPolicy`，
`create`、`availableRooms`、日历格子可点三处共用同一口径（含新单自己的入住前缓冲），
不会再出现"查询说可售、下单被拒"。

**退房不等于可售。** 退房时 `ROOM` 行被删掉、换上一行 `kind=CLEANING` 的
`[实际退房时刻, +clean_after]`，保洁在前台点"清扫完成"才真正释放。
原先"退房即删空"的做法会让系统立刻显示可售、而房间实际还没打扫，前台只能靠脑子记 ——
"可售"这个信号就不可信了。需求方确认的流程是"保洁收拾好点一下，前台才知道能卖"，
所以这条从加分项提为必做（W5）。`ROOM` 行与 `CLEANING` 行是接力关系、不并存，缓冲不会被算两遍。

### 占用时刻表 ≠ 真实占用（第五轮修正）

上面那条曾经配有一句**错误结论**："超时未退房会让房间一直挡着，保守且正确"。
实际不会 —— 冲突检测只比固定的 `block_end`，客人 13:00 还没走，13:00 之后的新单照样能通过检测，
于是把有人住的房卖出去。现在补了两条，都有测试：

- **提前入住**：`checkIn` 把 `block_start` 往前扩到实际入住时刻。计划 14:00 入住、客人 10:00 就住进去，
  那 10:00–14:00 也算占用，否则另一位前台能给这间房开一张 10:00–12:00 的钟点单。
- **超时未退房**：冲突查询加一条 `IN_USE 且 now() >= block_end 且请求触到 block_end 之后`。
  条件刻意收窄 —— 不是"只要在住就全挡"，那样会把"客人 14:00 入住、先把今晚 20:00 之后预卖出去"
  这种正常操作一起拒掉。**计划离店时刻是客人对房间的承诺，未到期按承诺算；到期还没退房才是未知状态。**
  日历上这种块会画成红色斜纹并在 tooltip 里写明超时多久，前台必须去处理（延长停留=加时，或办退房）。

`OccupancyTruthIT` 7 条逐一对应这两条 + 下面三条。

**幂等靠 `requestId`，且要校验内容。** 单号由服务端每次新生成，唯一键拦不住双击。
客户端带 UUID，服务端 `uk_request_id` + 命中即返回原单 —— 但**同标识不同内容必须拒**（`3005`）：
第一次提交其实成功了、响应超时、前台在同一个弹窗里改了时段重试，直接回放旧单会让前端
把"新时段开单成功"报给用户，而库里存的是旧时段。前端配合：弹窗内一改内容就换新的 requestId。

预占超时回收由 `HoldReleaseJob` 每 60 秒跑一次（`bnb.hold.release-interval-ms` 可调）。
它跨 bean 调内核的 `@Transactional` 方法 —— 同类内部调用会绕过 Spring 代理让事务静默失效。

连接池显式跑在 READ COMMITTED（`application.yml`）。这是第二道防线，**不是**上面第 1 条的修复本身：
`OversellUnderRepeatableReadIT` 把级别强行改回 RR 再跑真实入口并发，照样不超卖。

## 计费（W6）

钱的事全部由一个**不碰数据库、不读时钟的纯函数**算：`billing/PriceCalculator`。
`BillingService` 只负责凑输入、守状态机、落快照。这么拆不是洁癖 ——
取整、规则决胜、跨零点这几条口径只有纯函数才能被一组毫秒级、无容器的表驱动用例覆盖住。

算账的口径（六条，答辩会逐条被问）：

1. **起步价覆盖时长**：钟点房 = `min_hours × 60`；全日房 = 房型模板时长（14:00→次日 12:00 = 1320 分）。
   所以全日房整体是一口 `base_price`，**只有加时的部分**才按超时单价收。
2. **超时费向上取整到"计费粒度"**，粒度是房型上的 `charge_unit_minutes`（默认 60 = 不足一小时按一小时）。
   是配置项不是写死：同一张 9:00→12:20 的单，粒度 60 收 1 小时、粒度 30 收半小时、粒度 1 按分钟线性折。
3. **规则不叠加**。一个时刻只取一条，决胜 `priority` 升 → 房型级压过民宿级 → `id` 升（必须全序，
   否则同一张单两次算出两个价，快照就成了看运气）。
   规则维度是 `日期区间 ∧ 星期 ∧ 日内时段` 三块 AND，留空即不限；时段按半开区间 `[start, end)`，
   `start > end` 表示跨零点窗口（22:00→02:00）。
4. **起步价按入住时刻定价，超时段按各自起点定价**。跨节假日边界的长住单，起步用入住那天的价、
   后半段用假期价 —— 有意为之。
5. **取整只在计费单元一级**（HALF_UP 到分），明细金额是它包含的单元之和，合计是明细之和。
   **打印出来的分项永远加得上合计。**
6. **早退不退、晚走要补**：计费终点取 `max(计划离店, 实际退房)`，两种情况都会在账单上写一句人话提示。

**试算不落库，结算才落快照。** `bill` 表存当时的单价、分钟数和命中的规则名（`detail_json`），
改规则、改房型价都不回溯已结算账单；返回体的 `snapshot` 标志区分"实时试算"与"已结算快照"，
界面上必须显示这个区别，否则前台会把两者当成系统自相矛盾。

结算守的是内核第 3 条铁律：`update booking set status='SETTLED', amount=? where id=? and status='CHECKED_OUT'`
的影响行数才是裁判，赢了自己插 `bill` 行 —— 顺序反过来的话并发双击会靠唯一键报错回滚，
正确性来自约束冲突而不是来自状态机，错误信息还难看。

## 测试

```bash
mvn -B test
```

| 测试 | 证明什么 |
|---|---|
| `FlywaySchemaIT` | 迁移真的建出 8 张 InnoDB/utf8mb4 表；`uk_booking_kind` 确实是两列；8 个外键齐备；`uk_booking_bill` 单列唯一（一单一账单）；`idx_block` 列序正确；DATETIME 不被时区换算 |
| `BookingKernelIT` | **直接调内核**的边界用例：并发抢同一时段恰好一单成功；边界相接不算冲突；45 分钟清扫缓冲能挡住近邻单；跨零点占用覆盖次日上午；取消即删占用行并可再售；**反向对照：不走行锁时数据库自己不拦两个重叠区间**；非法区间被拒。<br>⚠ 它只覆盖内核那一层 —— 内核里 `FOR UPDATE` 恰好是事务第一条语句，所以快照问题测不出来。真实入口由下面两个类覆盖 |
| `OversellAuditIT` | 4 条，一组对照实验：① RR 下"锁后普通 SELECT"确实读到旧快照并超卖（机制）；② 同样的交错换成当前读就看不到冲突不存在（**证明解药是当前读**）；③ 真实入口 `BookingService.create` 并发 12 轮零超卖（回归防线）；④ 同一 `requestId` 重复提交返回原单 |
| `OversellUnderRepeatableReadIT` | 把连接池隔离级别**强行改回 RR** 再跑真实入口并发 12 轮。过 —— 说明修复不依赖 READ COMMITTED 配置 |
| `RoomCatalogIT` | 民宿→房型→房间三级增改查且列表带出上级名称；房间有历史订单禁删（含"订单已取消但仍有历史"这一例）；有下级数据禁删民宿与房型；状态筛选、房号搜索、翻页不重不漏；房型不能挂到别的民宿下 |
| `BookingFlowIT` | 16 条：HELD→CONFIRMED→IN_USE→CHECKED_OUT 全流转且退房释放占用；非法跃迁被拒并提示可达状态；改期改写占用、撞车时**旧占用回滚**、改回原时段不被自己挡住；加时延长占用块 / 撞车被拒 / 不推出可售窗口；售卖方式显式声明（钟点不许跨天、全日房区间必须等于模板）；预占过期回收只动 HELD；**确认与超时回收并发 15 轮，状态与占用行始终一致**；空闲区间含缓冲相减；可售房间随取消而回归 |
| `CalendarIT` | 6 条：窗口按切割点旋转而不是自然日；凌晨 2 点的单归前一个营业日且不重复出现在次日；跨零点全日房在同一窗口里是完整一块；退房与取消后块消失；只返回该店房间且维修/下架房也出现（前端灰显而不是消失）；块上带客人姓名与订单状态 |
| `OccupancyTruthIT` | 7 条：超时未退房时后续时段必须被挡；退房后恢复可售；提前入住把入住前的空档也占住；可售查询算上新单自己的入住前缓冲（与内核同口径）；加时必须真的变长（这条检查曾在重写时被删掉）；全日房可以加时/延迟退房；同一 `requestId` 带不同内容明确拒绝 |
| `OperationLogIT` | 4 条，走 MockMvc + 真实 session：一次完整流转留下开单/入住/退房三条且都带操作人；前后值确实是两份快照（记同一个引用会让审计白记）；房间上下架留痕；取消留痕。**必须走 HTTP 层** —— `username` 来自登录拦截器写的 `AuthContext`，直接调 service 会一路是 null 而没人发现 |
| `PriceCalculatorTest` | **30 条纯单测，不起容器、不连库**：14 行表驱动金额表（订满起步 / 短于起步不退 / 超时 61 分钟按两小时 / 同一张单在粒度 60·30·1 下的三种结果 / 周末倍率同时改写起步与超时 / 跨星期边界 / 深夜窗口与 22:00 整的半开区间边界 / 国庆一口价命中与不命中 / 全日房加时 / 倍率产生小数时四舍五入）；结构类（合计恒等于明细之和、连续同价并档、规则切换处必须断成两条、起步价标"打包"免被误当小时价乘）；决胜类（priority、房型级压民宿级、同分按 id 且与传入顺序无关、日期闭区间、跨零点时段、三块条件必须同时满足）。<br>另有一条**基准日期星期自检** —— 第一次写这张表时把 2026-10-08 当成周五（实际周四），正是这条当场把 5 个用例全打红 |
| `BillingIT` | 9 条，走 MockMvc + 真实 session：试算=结算=明细相加且 `booking.amount` 与 `bill.total_amount` 同源；**改房型价不回溯已结算账单**（同时证明新开一单确实用了新价，不是缓存）；规则命中改写金额且规则名进快照、删规则后快照仍读得出；停用规则不参与计价；粒度 60→30 让同一张单少收 10 元；超时补收与早退不退；只有 CHECKED_OUT 能结算且不能重复结算（账单恒为一行）；取消单试算返 `3007 NOTHING_TO_BILL`；全日房起步覆盖 22 小时、加时才收超时费 |
| `SeedPasswordHashTest` | V2 里的 admin 哈希能被 `BCryptPasswordEncoder` 验通（与登录校验同源） |

`*IT` 需要 Docker。surefire 已在 pom 里显式收 `**/*IT.java`，否则会出现"构建成功但一条集成测试没跑"的假绿。

改种子密码：`mvn -B test -Dtest=SeedPasswordHashTest`，读 `target/seed-admin-hash.txt`，粘回 V2。

## 业务口径（已与需求方确认，写进论文的需求分析）

| 场景 | 口径 |
|---|---|
| 电话预订 / 现场开房 / 平台订单 | **共用一个登记入口**，不按来源拆流程。本项目不做渠道同步，所以平台订单一律"由前台人工录入" —— 范围要在论文里写清楚 |
| 提前入住 | 允许，由前台自行判断；但**实际入住时刻要进系统**并扩展占用区间（见上节），否则会把已住人的房再卖一次 |
| 延迟退房 | 不做审批流。日历标红 + tooltip 提示超时时长，由人去沟通；要正式延长就走加时接口 |
| 退房后谁确认可出售 | 保洁。**已实现**：退房落 `CLEANING` 占用行，前台/保洁点"清扫完成"才释放（`POST /api/rooms/{id}/cleaning-done`）。日历上待清扫的块画成中性斜纹，点它即可释放。不加通知器或物联网设备，点一下就够 |
| 谁改了什么 | `operation_log` 记下开单/确认/入住/退房/加时/改期/取消/清扫/房间状态变更/结算，含操作人与前后值 JSON，界面在「操作日志」页 |
| 提前退房收不收 | **不退差价**，按原单已订时长收，账单上写一句"客人提前退房，不退差价" |
| 超时未退房收不收 | **按实际退房时刻补收超时费**（计费终点 = `max(计划离店, 实际退房)`）。与上一行合起来就是"早退不退、晚走要补" |
| 结算的时机 | 退房之后才结算 —— 住了多久到退房那一刻才定下来。日历上退房后那个块（待清扫）直接给"账单/结算"两个按钮，不用切页 |

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
- **`mysql < seed_demo.sql` 必须带 `--default-character-set=utf8mb4`。** 客户端默认按 latin1 读文件，
  而 SQL 文件是 UTF-8，结果是每个中文字节被再编码一次 —— "李文博"存成 9 个字符，前台显示乱码。
  Flyway 走 JDBC 不受影响，所以**只有演示数据是坏的、结构数据是好的**，很容易误判成前端问题。
  `db/demo/seed_demo.sql` 末尾有一条用 `char_length` 做的自检（输出 3 才对，出现 9 就是踩了这个坑），
  刻意不打印中文，免得在 cp936 终端里看不出差别。
- **房态日历的横轴从营业日切割点起转**，不是 00:00 起。窗口是 `[dayCut, 次日 dayCut)`，
  块按 `block_start/block_end`（含清扫缓冲）定位，所以一段没人住的空档也可能画着块 ——
  那是缓冲，不是 bug。
- **页面整片空白、控制台报 `Unhandled error during execution of render/setup function`，
  多半不是代码问题，是 Vite 的依赖预打包缓存脏了。** 反复改 `AppShell.vue` 这类被多处引用的
  组件的 import 列表时容易触发 —— W6 加"订单/价格规则"两页时**又复发一次**，触发点是给
  `@element-plus/icons-vue` 新增一个具名导入（`Ticket`），而该依赖已在缓存里。修法：
  `rm -rf frontend/node_modules/.vite` 然后重启 dev server。
  先确认端口没被上一个 vite 进程占着（它会静默改端口到 5174，你刷新的其实还是那个坏掉的 5173）。
  排查时先分清两种失败：图标本身坏不坏可以用 SSR 单测（`renderToString(h(m.Ticket))`），
  上次用它证明了 `Document` 没问题、锅在缓存 —— 别一上来就改业务代码。
- **演示流水里的 `amount` 是手写的**（写在计费引擎之前），和 `/bill/preview` 算出来的数并不都相等；
  已结算的历史行也没有 `bill` 快照行，界面上会显示"实时试算"而不是"账单快照"。
  真正点过结算的单不受影响（金额由 `PriceCalculator` 说了算并写回 `booking.amount`）。
  W8 整理演示数据时收掉：把那批行的状态改成 `CHECKED_OUT` 再逐笔调 `/settle` 生成真快照。
- **`mvn test` 不带 `clean` 时，删掉的资源文件在 `target/classes` 里的旧副本不会被清除** ——
  删了个 `V3__x.sql` 又新建 `V3__y.sql`，Flyway 会报 "Found more than one migration with version 3"，
  而源码目录里明明只有一个 V3。
- Windows 控制台是 cp936，`mvn` 的中文报错会乱码；需要读结果时落到文件再用编辑器打开。
  Git Bash 里含空格的 PATH 条目（`C:\Program Files\...`）不能让 `java` 直接执行，
  但 Maven 走 `JAVA_HOME` 是好的 —— `scripts/env.sh` 已用 `cygpath` 转成 POSIX 形式。
