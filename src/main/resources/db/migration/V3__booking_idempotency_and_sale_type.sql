-- ============================================================================
-- V3__booking_idempotency_and_sale_type.sql
--
-- 1) request_id：补上真正的事件幂等。原先文档里写的"booking_no 唯一 = 幂等"是虚的 ——
--    booking_no 由服务端每次请求新生成，前台双击会产生两个不同单号，唯一键一个都拦不住。
--    允许 NULL：MySQL 唯一索引不约束 NULL，历史数据与不传 requestId 的内部调用不受影响。
--
-- 2) sale_type：把"钟点 / 全日房"从推断改成声明。
--    原先用"是否跨天"推断是不是全日房，逆命题不成立 —— 22:00 → 次日 00:30 只有 2.5 小时，
--    却因为跨天绕过了起步时长校验。存下来之后，加时和改期也能沿用原方式校验，
--    而不是让前端在改期时顺手把类型改掉。
-- ============================================================================

alter table booking
  add column request_id varchar(64) null after booking_no,
  add column sale_type  varchar(16)  not null default 'HOURLY' after request_id,
  add unique key uk_request_id (request_id);
