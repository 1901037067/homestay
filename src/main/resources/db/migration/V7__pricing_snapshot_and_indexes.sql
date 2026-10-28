-- ============================================================================
-- V7__pricing_snapshot_and_indexes.sql
--
-- 【1】计费基准在下单时冻结
--
-- BillingService 算账要用两个<b>房型配置</b>参数：起步覆盖时长（钟点 = min_hours×60，
-- 全日 = 模板时长）和计费粒度。如果结算时才去读 room_type，那"下单后改房型"就会
-- 悄悄改变一张<b>未结算旧单</b>的金额：14:00→次日12:00 的全日单，把模板改成
-- 14:00→次日10:00 之后，22 小时的起步覆盖变成 20 小时，结算时凭空多出两小时超时费。
--
-- 分两类看：价格规则（rate_rule）按"结算时当前规则"解释是业务上说得通的——它本来就是
-- 随时间调整的；但产品结构参数（起步时长、模板、粒度）不该回溯，
-- 它们是"这张单当时是怎么卖的"。所以在这里快照下来。
--
-- 允许为 NULL：历史数据没有这一列，BillingService 遇到 NULL 就退回现算，
-- 行为与升级前一致。
--
-- 【2】两个索引
--   booking(status, hold_expire_at) —— 超时回收任务每 60 秒按这对字段扫一次，原来只有
--        (status, start_time) 用不上；
--   operation_log(action, created_at) —— 审计页默认按动作筛选 + 时间倒序。
-- 单机毕设的数据量下不加也不会死，但这两个是每 60 秒/每次打开页面都会跑一次的查询，
-- 加上迟早要用，成本只有几 KB。
-- ============================================================================

alter table booking
  add column covered_minutes      int null comment '下单时快照：起步价覆盖的分钟数' after amount,
  add column charge_unit_minutes  int null comment '下单时快照：超时费计费粒度（分钟）' after covered_minutes;

create index idx_status_hold on booking (status, hold_expire_at);

create index idx_log_action on operation_log (action, created_at);
