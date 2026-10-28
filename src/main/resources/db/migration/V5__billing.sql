-- ============================================================================
-- V5__billing.sql
--
-- 三件事：价格规则表、账单快照表、房型上的计费粒度列。
--
-- 【为什么规则要单独一张表而不是往房型上加几列】
-- 房型上的 base_price / extra_hour_price 是"平时价"，而真实诉求是在它上面叠覆盖：
-- 周六上浮、法定节假日一口价、夜间时段另计。这些维度的组合数是乘法长大的，
-- 摊平进房型列会得到十几个 nullable 列还是不够。规则行的模型是
-- "命中时段 → 改这段的单价"，房型价只是没有任何规则命中时的兜底。
--
-- 【为什么账单要存快照而不是结算时现算】
-- 规则会改，房型价也会改。若账单只存一个 total，过两个月调了价，
-- "这张单到底怎么算出来的"就永久查不回来了，对账只能靠猜。
-- detail_json 存逐条明细（含当时的单价与命中的规则名），
-- 几个金额列做冗余，是为了报表直接 sum 而不用解析 JSON。
--
-- 【没有用 CHECK 约束】
-- phpStudy 的 MySQL 8.0.12 早于 CHECK 生效的版本（8.0.16），写了也形同虚设，
-- 反而会造出"开发环境没约束、答辩机上也没有"的错觉。mode / weekdays 的合法性
-- 由 RateRuleService 校验，那里是唯一写入口。
-- ============================================================================

create table rate_rule (
  id           bigint primary key auto_increment,
  property_id  bigint not null,
  room_type_id bigint       null comment 'NULL=整家民宿通用；非空=只作用于该房型，且压过民宿级同优先级的规则',
  name         varchar(64)  not null,

  -- 命中条件三块都可留空，留空即"不限"。给了就必须同时满足（日期 ∧ 星期 ∧ 时段）。
  valid_from   date             null comment '按日期覆盖的起点（含）。用来做法定节假日',
  valid_to     date             null comment 'NULL=只覆盖 valid_from 当天',
  weekdays     varchar(20)      null comment '逗号分隔的 1..7（1=周一，7=周日），如 "6,7"=周末；NULL=不限星期',
  start_hour   time             null comment '日内窗口起点，NULL=全天',
  end_hour     time             null comment '可跨零点：22:00→02:00 表示夜里到凌晨',

  mode         varchar(16) not null comment 'OVERRIDE=该时段单价直接等于 value（元/小时或一口价）；MULTIPLY=原价 × value',
  value        decimal(10,2) not null,
  priority     int not null default 100 comment '数字小者优先。决胜顺序见 RateRuleSelector：priority → 房型级 > 民宿级 → id',
  enabled      tinyint(1) not null default 1,
  created_at   datetime not null default current_timestamp,
  updated_at   datetime not null default current_timestamp on update current_timestamp,
  key idx_rule_scope (property_id, enabled, priority),
  constraint fk_rule_property foreign key (property_id) references property(id),
  constraint fk_rule_type     foreign key (room_type_id) references room_type(id)
) engine innodb default character set utf8mb4;

-- 结算一次写一行，booking_id 唯一：一张单只有一张账单。
-- 不允许"重新结算覆盖同一行"——SETTLED 在状态机里是终态，真要改价走人工红字，别把快照改掉。
create table bill (
  id                   bigint primary key auto_increment,
  booking_id           bigint not null,
  room_no              varchar(32) not null comment '快照：房号日后被改也不影响这张历史账单',
  guest_name           varchar(64) not null,
  sale_type            varchar(16) not null,
  calc_start           datetime not null comment '计费区间起点＝下单开始时间',
  calc_end             datetime not null comment '计费终点＝计划离店与实际退房取较晚者（早退不退、晚走要补）',
  covered_minutes      int not null comment '起步价覆盖的分钟数：钟点＝min_hours×60，全日＝房型模板时长',
  charge_unit_minutes  int not null comment '当时的超时费计费粒度，快照下来才能复算',
  base_amount          decimal(10,2) not null comment '起步价合计（含当时命中的规则）',
  overage_amount       decimal(10,2) not null comment '超时费合计',
  total_amount         decimal(10,2) not null comment '等于 base + overage，也等于 detail_json 各条之和',
  detail_json          text not null comment '逐条明细：时段、分钟数、单价、命中的规则名',
  settled_at           datetime not null,
  operator_id          bigint null,
  operator_name        varchar(64) null,
  created_at           datetime not null default current_timestamp,
  -- 命名唯一键而不是内联 unique：一张单只有一张账单，且断言时能按名字查列（FlywaySchemaIT）
  unique key uk_booking_bill (booking_id),
  key idx_bill_settled (settled_at),
  constraint fk_bill_booking foreign key (booking_id) references booking(id)
) engine innodb default character set utf8mb4;

-- 超时费"不足一小时按一小时"是行业惯例，但这个惯例本身是个策略，不是公理：
-- 有人按半小时收，有人按分钟线性折。做成列而不是写死在代码里，
-- 表驱动单测才测得出粒度这一维，答辩被追问时也拿得出配置项。
alter table room_type
  add column charge_unit_minutes int not null default 60
    comment '超时费计费粒度（分钟）。超时时长向上取整到它的整数倍' after extra_hour_price;

-- 演示规则：周末钟点房上浮 20%；云栖全日房国庆一口价。
-- 放迁移而不是 seed_demo.sql：这两行是"功能存在"的证据，不是流水数据，
-- 新库一建好就该能在价格规则页看到它们，否则该页第一次打开是空的，看着像没做完。
insert into rate_rule
  (id, property_id, room_type_id, name, valid_from, valid_to, weekdays, start_hour, end_hour,
   mode, value, priority, enabled) values
  (1, 1, null, '周末钟点上浮', null, null, '6,7', null, null, 'MULTIPLY', 1.20, 100, 1),
  (2, 1, 3,    '国庆全日房一口价', '2026-10-01', '2026-10-07', null, null, null, 'OVERRIDE', 488.00, 10, 1),
  (3, 1, null, '深夜时段加价', null, null, null, '22:00:00', '02:00:00', 'MULTIPLY', 1.50, 50, 1);
