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
├── stats                  StatsAggregator —— 报表聚合，同样是纯函数（切割/分摊/归属全在这里）
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
                           billing 价格规则 / stats 报表 / system 操作日志（全部路由级懒加载）
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
bash scripts/reset-demo.sh            # 建库 → Flyway 全部迁移 → 灌演示流水（后端需停）
mvn -B spring-boot:run                # 后端 8080，Swagger 在 /swagger-ui.html
bash scripts/settle-demo.sh           # 历史单走真实结算，生成账单快照（后端起来之后）
cd frontend && npm install && npm run dev   # 前端 5173
```

浏览器打开 http://localhost:5173 ，账号 `admin` / `Admin@123`（`staff` 同密码，用于演示操作日志）。

`reset-demo.sh` 会 drop database，**跑之前先停后端**。演示中途数据点乱了可以重跑一次，30 秒复原。

### 整栈部署（三服务）

```bash
docker compose --profile app up -d --build   # MySQL + 后端 + 前端（nginx 托管 dist，/api 反代）
# 打开 http://localhost:5173
```

**已实测通过**（2026-10-29）：后端容器 `healthy`、nginx 静态页 200、`/api/ping` 反代通、
容器内 Flyway 校验 6 个迁移后 5.6 秒启动、登录后经 nginx 取到报表数据。
后端镜像 332MB（多阶段构建，运行镜像里没有 Maven 与源码）。

两个网络坑都已在镜像里规避，换机器时留意：

- 构建容器**拿不到外网直连**时会卡在下载上（本机实测 `npm ci` 288 秒后 ETIMEDOUT）。
  所以后端用 `docker/maven-settings.xml`（阿里云）、前端用 `ARG NPM_REGISTRY`
  （默认 npmmirror）。能直连的机器用
  `--build-arg NPM_REGISTRY=https://registry.npmjs.org` 覆盖，或删掉 Dockerfile 里那两行。
- 开发期仍只需要 `docker compose up -d`（只起库）。两者共用同一份 mysql 配置 ——
  用 profile 而不是两个 compose 文件，是为了让字符集/时区这类"改一边忘一边就出事"的配置只有一处。

前端不直连 8080，开发时走 Vite dev proxy、部署时走 nginx 反代，两条路都是同源 cookie，
后端不必开 CORS 白名单。

> 开发（热更新）与部署（容器）**不要同时跑**：两者都占 8080/5173。
> 切回开发时先 `docker compose --profile app stop backend frontend`（mysql 留着）。

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

九个页面：概览 / 房态日历 / 订单 / 房间 / 房型 / 民宿 / 价格规则 / 报表 / 操作日志。
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
GET  /api/stats/report           ?propertyId&from&to   指标卡 + 日序列 + 热度 + 排行（一次扫描）
GET  /api/stats/export           ?propertyId&from&to   导出 CSV（UTF-8 BOM，Excel 直接打开）
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

**入住也是一次"兑付"，要过同一道闸门。** 开单时的检查只拦"新订单"，拦不住"已经存在的订单去兑付"：
A 单订到 12:00 却没退、B 单 14:00 按计划入住 —— 如果入住不看房态，两个人就进了同一间房。
所以 `checkIn` **每次都**做四件事：房间仍在售、**整段预订还没过去**、这间房此刻没有别人的占用
（超时在住 / 待清扫）、要前扩的那一段也没人占。做法统一成"删掉自己的占用行 →
按 `[min(实际, 计划起点), block_end)` 当前读检测 → 重写"，检测不过整笔回滚。

两条规则各自的来历：**只做"提前入住"分支**时，按时入住那条支路完全没设防（两个人进同一间房）；
补上之后仍有洞 —— 客人 14:00 才到、单子 10:00–12:00 已经结束，此刻只查历史窗口 `[10:00, 12:30)`
是查不到 13:00 那单的，于是"入住成功"。所以"到店时刻 ≥ 预订结束时刻"直接拒，引导改期或重开单
（半开区间：恰好卡在 `end` 也算过期）。

**退房不等于可售。** 退房时 `ROOM` 行被删掉、换上一行 `kind=CLEANING` 的
`[实际退房时刻, +clean_after]`，保洁在前台点"清扫完成"才真正释放。
原先"退房即删空"的做法会让系统立刻显示可售、而房间实际还没打扫，前台只能靠脑子记 ——
"可售"这个信号就不可信了。需求方确认的流程是"保洁收拾好点一下，前台才知道能卖"，
所以这条从加分项提为必做（W5）。`ROOM` 行与 `CLEANING` 行是接力关系、不并存，缓冲不会被算两遍。

**而 `CLEANING` 行代表的是"一件还没做完的事"，不是一段固定时间。** 第三轮审阅指出：
它当时仍是 `[12:00, 12:30]` 这样的有限区间，于是"预计 45 分钟一过就自动放行"，
和文档里"保洁确认才释放"并不一致。现在冲突查询、可售查询、日历格子三处同口径加了
一条：未确认的清扫行从它的起点起**持续**挡房。
代价要写明白 —— 保洁忘了点，这间房连**以后的时段**都卖不出去。这是"确认式闸门"的固有代价，
不是 bug；缓解办法是让那一行始终看得见、一键就能释放（见下节），而不是把闸门改回到点自动放行。

### 改变实际占用范围的动作，也要跑冲突检测（第三轮审阅第 1 条）

防超卖不能只盯"下单"这一个入口。`checkIn` 会把 `block_start` 往前扩到实际入住时刻，
而修复前它**只判断"要不要前移"就直接改写**，完全没跑冲突检测 —— 于是：

> A 单 10:00–12:00、B 单 14:00–18:00，两单本来互不相干；给 B 办 10:00 提前入住，
> B 的占用就变成 10:00–18:00，压在 A 头上。

两个原本不冲突的预订被一次入住操作叠到了一起，而**全项目的判定都是从"占用行互不重叠"
这条不变量推出来的**，所以它一旦被破坏，后面所有结论都不再可靠。
同一处还有第二个问题：`extend` 用 `booking.startTime` 当新行的起点，
于是提前入住扩出来的那段被抹平，又能被别人开走。

两条都按内核既有的套路修了（先删自己 → 当前读检测 → 重写，失败随事务回滚），
并补了测试。另外把"同房间两两重叠的行数必须恒为 0"做成一个查询助手，
在每个会改写占用的用例末尾顺手断一次 —— 只验"这次操作对不对"是不够的，
要验的是它有没有破坏全局前提。

### 异常任务必须留在视野里（第三轮审阅第 6 条）

日历原先只返回"与当天窗口相交"的占用行。于是昨天该退房却还没走的那位，
他的块整段落在今天窗口之左 —— 前台看到"这间房空着"，开单时后端又提示仍被占用；
更糟的是**退房、清扫完成这些按钮都挂在块上，块看不见就等于没有处理入口**。
现在查询额外返回两类行（超时在住、未确认清扫），前端把它们夹到窗口左缘画出 30 分钟宽的一小段
（再窄就点不中）。配合上面那条持续挡房的规则，"看得见 → 点得动 → 才放行"才真正闭环。

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

算账的口径（七条，答辩会逐条被问）：

1. **起步价覆盖时长**：钟点房 = `min_hours × 60`；全日房 = 房型模板时长（14:00→次日 12:00 = 1320 分）。
   所以全日房整体是一口 `base_price`，**只有加时的部分**才按超时单价收。
2. **超时费向上取整到"计费粒度"**，粒度是房型上的 `charge_unit_minutes`（默认 60 = 不足一小时按一小时）。
   是配置项不是写死：同一张 9:00→12:20 的单，粒度 60 收 1 小时、粒度 30 收半小时、粒度 1 按分钟线性折。
3. **规则不叠加**。一个时刻只取一条，决胜 `priority` 升 → 房型级压过民宿级 → `id` 升（必须全序，
   否则同一张单两次算出两个价，快照就成了看运气）。
   规则维度是 `日期区间 ∧ 星期 ∧ 日内时段` 三块 AND，留空即不限；时段按半开区间 `[start, end)`，
   `start > end` 表示跨零点窗口（22:00→02:00）。
4. **规则要声明自己改的是哪一笔钱**（`applies_to` = `ALL` / `BASE` / `OVERAGE`）。
   房费是打包价、超时是元/小时，同一个数值套两种量纲必然算出荒谬金额：
   "平时全日 388、超时 40/小时、假日一口价 488"，若那条 OVERRIDE 连小时价也一起变成 488，
   延迟退房一小时就收出 976，而规则名字明明写的是"房费 488"。
   起步段只接受 `ALL`/`BASE`，超时段只接受 `ALL`/`OVERAGE`；演示数据里的国庆规则是 `BASE`。
5. **起步价按入住时刻定价，超时段按各自起点定价**。跨节假日边界的长住单，起步用入住那天的价、
   后半段用假期价 —— 有意为之。
6. **取整只在计费单元一级**（HALF_UP 到分），明细金额是它包含的单元之和，合计是明细之和。
   **打印出来的分项永远加得上合计。**
7. **早退不退、晚走要补**：计费终点取 `max(计划离店, 实际退房)`，两种情况都会在账单上写一句人话提示。

**计费基准在下单时冻结。** 起步覆盖时长与计费粒度这两个<b>房型结构参数</b>会随配置改，
所以下单时就把答案快照到 `booking.covered_minutes / charge_unit_minutes`，结算优先读快照
（历史行没有快照才现算）。不这样做的话：14:00→次日 12:00 的全日单，把模板改成 14:00→次日 10:00，
结算时起步覆盖从 22 小时变 20 小时，凭空多出两小时超时费。
价格规则（`rate_rule`）相反，按"结算时的当前规则"解释 —— 它本来就是随时间调整的。

**试算不落库，结算才落快照。** `bill` 表存当时的单价、分钟数和命中的规则名（`detail_json`），
改规则、改房型价都不回溯已结算账单；返回体的 `snapshot` 标志区分"实时试算"与"已结算快照"，
界面上必须显示这个区别，否则前台会把两者当成系统自相矛盾。

结算守的是内核第 3 条铁律：`update booking set status='SETTLED', amount=? where id=? and status='CHECKED_OUT'`
的影响行数才是裁判，赢了自己插 `bill` 行 —— 顺序反过来的话并发双击会靠唯一键报错回滚，
正确性来自约束冲突而不是来自状态机，错误信息还难看。

## 报表（W7）

全部按**营业日**切割，而且切割这件事只有一处实现：`stats/StatsAggregator` 是纯函数，
SQL 只做范围扫描。理由和计费一样 —— 归属边界（凌晨算哪天、跨零点摊到哪几格）写在 SQL 里
每个分支都得连库造数据才能验，写成纯函数就被 18 条用例钉住了。

五条口径：

1. **营业日归属** `bd(t) = date(t - dayCut)`，归的是**入住时刻**所在营业日。
   一张 14:00→次日 12:00 的全日单整段算入住那天，不会被劈成两天房晚。
2. **出租率 = 当天卖出去的不同房间数 / 在售房间数**。不用"按小时加权占用率"：
   钟点房卖 4 小时和三小时房的率没法放进同一个分母解释，而"今天有几间房被卖出去过"
   是前台和店长都能立刻听懂的话。平均出租率按总房晚加权，不是把每天的率再平均。
   **已知简化**：分母取的是"当前"在售房间数，房间下架后历史出租率会跟着变（甚至超过 100%）。
   页面上与论文里都要写明这一点，别让人把它当成严格的时序指标。
3. **营收只认已结算**（`SETTLED`）。已退房未结账的**单列单数**（`pendingBookings`），不给金额 ——
   `amount` 在结算那一刻才写回，退房后一直是 0，按它求和永远是 0 元，只有手写数据的测试才显得对。
4. **热度按小时切片**，横轴从切割点起转（06,07,…,05），凌晨 2 点落在第 20 格。
   页面上留了「从 00:00 起（对照）」开关，一眼能看出错误口径长什么样，论文里可以直接放对比图。
5. **热度的归属口径与房晚不同**：房晚/营收按"入住营业日"，热度按"住宿区间是否与窗口相交"。
   一笔昨天 14:00 → 今天 12:00 的单不占今天的房晚，但确实占着今天上午的六格。
   取数 SQL 因此用 `start_time < to and end_time > from`（区间相交），而不是"入住时刻落在窗口内"。

一个接口出三张图：原本按实施计划拆成 overview / hourly-heat / room-ranking 三个接口，
实现时合成 `GET /api/stats/report` —— 三个接口各扫一遍库，期间有新单进来就会让指标卡与图表互相矛盾，
答辩时被指着问"这两个数为什么对不上"很致命。一次扫描、一份快照，是把"三个视图同源"变成结构上的保证。

导出是 **CSV + UTF-8 BOM**（Excel 双击直接正确显示中文），不是 .xlsx：为一张一次能装下的表
引入 Apache POI 要背十几兆依赖，而这份数据的价值在"能贴进论文/发给店长看"，不在格式本身。
换 xlsx 的改动被收敛在 `StatsService.toCsv` 一个方法里。

## 压测与反向对照（W8）

完整报告在 [docs/防超卖压测报告.md](docs/防超卖压测报告.md)，数字由压测台自动产出。三条命令：

```bash
mvn -B test -Dtest=ConcurrencyBenchIT         # 10/20/50 三档并发，各 10 轮
mvn -B test -Dtest=ConcurrencyBenchPool50IT   # 同上但连接池 50，对照 7.4 那条坑
mvn -B test -Dtest=OversellWithoutLockIT      # 反向对照：必须出现超卖
```

结论两句话：**正向** 每轮 10/20/50 线程抢同一间房同一时段，永远只有 1 单成功、其余全是 3003，
零超卖零异常；**反向对照** 保留房间行锁、只把冲突检测从当前读换回普通 SELECT（并强行拉回 RR），
10 轮 10 线程的 100 个请求**全部成功**。也就是说：互斥由行锁给，正确性由"判定要读最新已提交数据"给，
两者缺一不可 —— 这正是论文第 4 章的主论点。

压测台打的是**真实入口**而不是内核：内核里 `FOR UPDATE` 恰好是事务第一条语句，用内核压测会把
"RR 读视图建立在拿锁之前"这个真问题盖住（W3 就是这么漏掉 12/12 超卖的）。

**延迟对每个请求都计时**（含被拒），并报出样本数——只统计成功者会让十轮只剩 10 个样本，
且全是同一条成功路径。修正后 p50 随并发上升：101 → 135 → 196ms，与行锁等待次数（90 → 189 → 490）
同向。“被拒的请求很快”是个错误直觉：它们同样要先排队拿锁再判冲突。

延迟只报方向和量级，不报倍数：同一档 50 线程两次运行 p95 分别是 352ms 与 814ms，
10 轮样本的抖动足以淹没趋势。**锁等待**比延迟稳（累计量），也更值得引。

RC 与 RR 的对照（`ConcurrencyBenchRepeatableReadIT`）：两边都零超卖、锁等待次数相同（490），
RR 的延迟与累计等待时间更大。**只报差异，不做归因** —— 这次实验没打点版本链、也没隔离其它变量，
两档是两次独立运行。

## 测试

```bash
mvn -B test
```

| 测试 | 证明什么 |
|---|---|
| `FlywaySchemaIT` | 迁移真的建出**9 张** InnoDB/utf8mb4 表（精确集合，多一张少一张都红）；9 个外键精确齐备；`uk_booking_kind` 两列、`uk_booking_bill` 单列唯一；`idx_block` 列序正确；`LocalDateTime` 经 JDBC **真写再真读**回一致（不只是比对 SQL 字面量） |
| `BookingKernelIT` | **直接调内核**的边界用例：并发抢同一时段恰好一单成功；边界相接不算冲突；45 分钟清扫缓冲能挡住近邻单；跨零点占用覆盖次日上午；取消即删占用行并可再售；**反向对照：不走行锁时数据库自己不拦两个重叠区间**；非法区间被拒。<br>⚠ 它只覆盖内核那一层 —— 内核里 `FOR UPDATE` 恰好是事务第一条语句，所以快照问题测不出来。真实入口由下面两个类覆盖 |
| `OversellAuditIT` | 4 条，一组对照实验：① RR 下"锁后普通 SELECT"确实读到旧快照并超卖（机制）；② 同样的交错换成当前读就看不到冲突不存在（**证明解药是当前读**）；③ 真实入口 `BookingService.create` 并发 12 轮零超卖（回归防线）；④ 同一 `requestId` 重复提交返回原单 |
| `OversellUnderRepeatableReadIT` | 把连接池隔离级别**强行改回 RR** 再跑真实入口并发 12 轮。过 —— 说明修复不依赖 READ COMMITTED 配置 |
| `RoomCatalogIT` | 民宿→房型→房间三级增改查且列表带出上级名称；房间有历史订单禁删（含"订单已取消但仍有历史"这一例）；有下级数据禁删民宿与房型；状态筛选、房号搜索、翻页不重不漏；房型不能挂到别的民宿下 |
| `BookingFlowIT` | 16 条：HELD→CONFIRMED→IN_USE→CHECKED_OUT 全流转且退房释放占用；非法跃迁被拒并提示可达状态；改期改写占用、撞车时**旧占用回滚**、改回原时段不被自己挡住；加时延长占用块 / 撞车被拒 / 不推出可售窗口；售卖方式显式声明（钟点不许跨天、全日房区间必须等于模板）；预占过期回收只动 HELD；**确认与超时回收并发 15 轮，状态与占用行始终一致**；空闲区间含缓冲相减；可售房间随取消而回归 |
| `CalendarIT` | 7 条：窗口按切割点旋转而不是自然日；凌晨 2 点的单归前一个营业日且不重复出现在次日；跨零点全日房在同一窗口里是完整一块；退房与取消后块消失；只返回该店房间且维修/下架房也出现（前端灰显而不是消失）；块上带客人姓名与订单状态；**超时在住与未确认清扫的行即使整段落在窗口之左也必须返回**（块看不见 = 退房/清扫的入口跟着一起消失） |
| `OccupancyTruthIT` | 22 条：超时未退房时后续时段必须被挡；退房后恢复可售；提前入住把入住前的空档也占住；可售查询算上新单自己的入住前缓冲（与内核同口径）；加时必须真的变长（这条检查曾在重写时被删掉）；全日房可以加时/延迟退房；同一 `requestId` 带不同内容明确拒绝。<br>第三轮审阅补 4 条：**提前入住不能把占用扩进别人已订的时段**（被拒后起点与状态都不许变）、合法的提前入住仍要真把前段占住（边界相接不算重叠）、**提前入住之后再加时起点必须保留**、**未确认的清扫行过了预计终点照样挡房**且可售查询同口径。每条末尾顺手断一次"同房间两两重叠的行数 = 0" |
| `OperationLogIT` | 4 条，走 MockMvc + 真实 session：一次完整流转留下开单/入住/退房三条且都带操作人；前后值确实是两份快照（记同一个引用会让审计白记）；房间上下架留痕；取消留痕。**必须走 HTTP 层** —— `username` 来自登录拦截器写的 `AuthContext`，直接调 service 会一路是 null 而没人发现 |
| `PriceCalculatorTest` | **34 条纯单测，不起容器、不连库**：14 行表驱动金额表（订满起步 / 短于起步不退 / 超时 61 分钟按两小时 / 同一张单在粒度 60·30·1 下的三种结果 / 周末倍率同时改写起步与超时 / 跨星期边界 / 深夜窗口与 22:00 整的半开区间边界 / 国庆一口价命中与不命中 / 全日房加时 / 倍率产生小数时四舍五入）；结构类（合计恒等于明细之和、连续同价并档、规则切换处必须断成两条、起步价标"打包"免被误当小时价乘）；决胜类（priority、房型级压民宿级、同分按 id 且与传入顺序无关、日期闭区间、跨零点时段、三块条件必须同时满足）。<br>另有一条**基准日期星期自检** —— 第一次写这张表时把 2026-10-08 当成周五（实际周四），正是这条当场把 5 个用例全打红 |
| `BillingIT` | 11 条，走 MockMvc + 真实 session：试算=结算=明细相加且 `booking.amount` 与 `bill.total_amount` 同源；**改房型价不回溯已结算账单**（同时证明新开一单确实用了新价，不是缓存）；规则命中改写金额且规则名进快照、删规则后快照仍读得出；停用规则不参与计价；粒度 60→30 让同一张单少收 10 元；超时补收与早退不退；只有 CHECKED_OUT 能结算且不能重复结算（账单恒为一行）；取消单试算返 `3007 NOTHING_TO_BILL`；全日房起步覆盖 22 小时、加时才收超时费；**清空规则的日期/星期/房型限制必须真的落库并改变计价**（MyBatis-Plus 默认更新策略会跳过 null 字段） |
| `StatsAggregatorTest` | **18 条纯单测，不起容器**：营业日归属与横轴旋转的表驱动（含"06:00 前算前一天"与"同一切割点换成 04:00 就归另一天"）；跨零点全日房只算一个房晚但热力摊两个营业日；**只查后一天时，前一天入住跨进窗口的住宿仍要出现在热力图里**；凌晨单落在轴尾；取消/释放单不计；营收只认已结算、待结算按单数；超时/早退计数；出租率按不同房间数算；区间外单不进任何一天；排行次序；平均出租率按加权而非均值 |
| `StatsIT` | 6 条，走 MockMvc + 真 MySQL：日序列与合计（参数从 SQL 一路到 JSON 都对）、**05:00 的单在真实数据上确实归前一天且查后一天时不出现**、热度横轴旋转（slot 20 / hour 2）、排行只统计区间内、民宿之间隔离、CSV 带 BOM 与合计行 |
| `ConcurrencyBenchIT` | 10/20/50 三档并发 × 10 轮，每轮 N 线程经 `CyclicBarrier` 抢同一间房同一时段：断言**每轮恰好 1 单成功**、其余全是 3003、无死锁/超时；记录 InnoDB 行锁等待；并把 markdown 表格写到 `target/压测报告.md` |
| `ConcurrencyBenchPool50IT` | 同上但连接池 50，与默认 10 对照 —— 证明串行点是房间行锁，不是连接数（实施计划 7.4） |
| `ConcurrencyBenchRepeatableReadIT` | 同条件把隔离级别换成 RR：正确性不变（零超卖），比较锁等待 —— 堵住"是不是恰好靠 RC 配置才对"的追问 |
| `OversellWithoutLockIT` | **反向对照**：`@SpyBean` 把 `lockOverlapping` 换成普通 `countOverlap`、隔离级别强行拉回 RR，其余不动 → 断言"必须出现超卖"。它若变绿在没有超卖，说明对照失效，正向的"零超卖"也就不再证明任何事 |
| `SeedPasswordHashTest` | V2 里的 admin 哈希能被 `BCryptPasswordEncoder` 验通（与登录校验同源） |

`*IT` 需要 Docker。surefire 已在 pom 里显式收 `**/*IT.java`，否则会出现"构建成功但一条集成测试没跑"的假绿。

改种子密码：`mvn -B test -Dtest=SeedPasswordHashTest`，读 `target/seed-admin-hash.txt`，粘回 V2。

## 业务口径（已与需求方确认，写进论文的需求分析）

| 场景 | 口径 |
|---|---|
| 电话预订 / 现场开房 / 平台订单 | **共用一个登记入口**，不按来源拆流程。本项目不做渠道同步，所以平台订单一律"由前台人工录入" —— 范围要在论文里写清楚 |
| 提前入住 | 允许，由前台自行判断；但**实际入住时刻要进系统**并扩展占用区间（见上节），否则会把已住人的房再卖一次 |
| 延迟退房 | 不做审批流。日历标红 + tooltip 提示超时时长，由人去沟通；要正式延长就走加时接口 |
| 退房后谁确认可出售 | 保洁。退房落 `CLEANING` 占用行，前台/保洁点"清扫完成"才释放（`POST /api/rooms/{id}/cleaning-done`）。**未确认前这间房从清扫起点起持续不可售，包括以后的时段** —— 确认式闸门的固有代价，缓解是让那一行始终显示在当天日历左缘、一键即可释放。日历上待清扫的块画成中性斜纹。不加通知器或物联网设备，点一下就够 |
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
- **演示数据要分两步**：`reset-demo.sh` 灌出来的历史订单是「已退房未结算、金额为 0」，
  金额故意不手写 —— 手写的数和 `/bill/preview` 算的对不上，演示时点开历史账单会显示
  "实时试算"而不是"账单快照"。起后端之后必须再跑 `bash scripts/settle-demo.sh`，
  它逐笔调 `/settle`，于是这 13 笔走的是与前台点"结算"完全相同的代码路径，
  生成真快照（含当时命中的规则名），报表营收也随之变成引擎算出来的数。
  漏跑这一步的表现很好认：订单列表里金额全是「未结算」。
- **同一事务里"加锁后再 `selectById`"会命中 MyBatis 一级缓存，读到加锁前的旧对象。**
  缓存的作用域是 SqlSession，而 Spring 下正好等于一个事务。所以"拿到锁之后重新查一次房间"
  看起来刷新了、其实没有：另一个事务刚把房间改成维修，这里读到的仍是 `ON_SALE`（有测试钉着）。
  修法是让**锁查询直接返回整行**（`select * ... for update`），一条语句同时完成加锁与读最新行；
  订单同理用 `findByIdForUpdate`。凡是"基于锁内最新状态下判断"的地方，都别再补一次普通查询。
- **改了 `.vue` 但浏览器里跑的还是旧编译产物**：症状是代码明明在文件里（`grep` 得到），
  页面行为却是旧的；再一看 `getAttribute('style')` 里少了一个属性，其实是那格宽度被算成了
  负数百分比、被 CSS 丢掉。W6 收尾时踩过一次，重启 vite（顺带 `rm -rf node_modules/.vite`）即好。
  排查顺序建议：`curl http://127.0.0.1:5173/src/.../X.vue | grep 关键字` —— 直接问 dev server
  要它正在提供的模块内容，比在浏览器里猜快得多。
  另外 `navigate_page` 到只有 hash 不同的地址**不会重载文档**，验证前端改动要换整个 URL。
- **`mvn test` 不带 `clean` 时，删掉的资源文件在 `target/classes` 里的旧副本不会被清除** ——
  删了个 `V3__x.sql` 又新建 `V3__y.sql`，Flyway 会报 "Found more than one migration with version 3"，
  而源码目录里明明只有一个 V3。
- Windows 控制台是 cp936，`mvn` 的中文报错会乱码；需要读结果时落到文件再用编辑器打开。
  Git Bash 里含空格的 PATH 条目（`C:\Program Files\...`）不能让 `java` 直接执行，
  但 Maven 走 `JAVA_HOME` 是好的 —— `scripts/env.sh` 已用 `cygpath` 转成 POSIX 形式。
