-- ============================================================================
-- V8__user_property_scope.sql
--
-- 【1】账号按民宿隔离
--
-- 之前 sys_user 只有"能不能登录"，没有任何"能看哪些数据"：admin 和 staff 登录后
-- 看到的是同两家店的全部房间与订单。前台系统一旦真开第二家店，这就是事故源 ——
-- 张店的前台能改李店的价、结李店的账。
--
-- 隔离维度只有一个：民宿。所以只加一列，不引入 role 列。
--
--   property_id IS NULL  → 店长：看全部门店，且能管账号、能建/删民宿
--   property_id = <id>   → 该民宿前台：只看这一家
--
-- 为什么不另设 role 列：那样就有两个真相。"role=STAFF 但 property_id 为空"
-- 这种组合到底是全店前台还是无店店长，只能再写一条规则去解释，而规则本身又会漂移
-- （本项目已被这类"两处各存一份、后来对不上"的口径咬过不止一次）。
-- 一列 NULL/非空 就把矩阵说完了，界面标签、接口判定、测试断言都从它派生。
--
-- 【2】外键
-- 与其它 9 条外键同口径：关系存在就一定有约束，删民宿时由 PropertyService.delete
-- 先给出人话提示（见那里的账号计数守卫），而不是让数据库抛 1451。
--
-- 【3】审计也要隔离
-- 操作日志的摘要里带客人姓名与房号（"302 退房（B…），转清扫 30 分钟"），
-- 所以它同样是民宿数据 —— 不给它归属，前台一开审计页就能看到别家店的客人。
-- 这一列刻意<b>不加外键</b>：日志是历史凭证，不该因为某家店后来被删掉而插不进去，
-- 也不该在删店时被 1451 挡住（民宿能不能删由 PropertyService.delete 的规则说了算）。
--
-- 【4】演示数据
-- staff（前台小张）绑到云栖民宿。这样登录 staff 就能当场演示隔离：
-- 民宿列表只剩一行、报表只剩一家、给山语的房开单会被拒。
-- ============================================================================

alter table sys_user
  add column property_id bigint null comment '所属民宿：NULL=店长（全部门店），非空=该民宿前台' after display_name,
  add constraint fk_user_property foreign key (property_id) references property (id);

alter table operation_log
  add column property_id bigint null comment '这条动作归属的民宿；NULL=跨店或系统级动作（登录、账号管理）' after entity_id;

create index idx_log_property on operation_log (property_id, id);

update sys_user set property_id = 1 where username = 'staff';
