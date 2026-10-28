-- ============================================================================
-- db/demo/seed_demo.sql —— 演示流水（不进 Flyway，手动灌）
--
--   docker compose exec -T mysql mysql -uroot -pbnb_dev_123 bnb < db/demo/seed_demo.sql
--
-- ⚠ 本文件体现总纲 A 的一条重要后果，W7 报表必须照此写：
--   occupancy 是「此刻谁把这段时间占住了」的活表，退房/取消就删行。
--   所以历史订单在 occupancy 里查不到 —— 入住率、营收、分时段热度一律读 booking。
--   要是给历史订单也留占用行，房态图会长出幽灵方块、入住率会算成 100%。
--
-- 时间全部用 CURDATE() 相对生成，任何时候灌都"像刚发生的事"。
-- 时段分布刻意做成晚间高峰，W7 的分时段热力图一开就有形状。
--
-- ⚠ 历史订单在这里一律是 CHECKED_OUT、amount=0.00，**不是** SETTLED。
--   原因：金额与账单快照必须由计费引擎产出，SQL 里手写一个数就会和 /bill/preview 对不上，
--   演示时点开历史账单会显示"实时试算"而不是"账单快照"。
--   所以灌完数据、后端起来之后，还要跑一次： bash scripts/settle-demo.sh
--   它逐笔调 /api/bookings/{id}/settle，于是这批历史单走的是和前台点"结算"完全一样的代码路径，
--   生成真实的 bill 快照（含当时命中的规则名），报表营收也随之变成引擎算出来的数。
-- ============================================================================

-- bill 有指向 booking 的外键，必须比 booking 先删；顺序反了整段清理会卡在 1451 上
delete from bill;
delete from occupancy;
delete from booking;

-- ---------------------------------------------------------------------------
-- 1) 历史已完成订单：只有 booking 行，没有 occupancy 行（已退房 = 已释放）
-- ---------------------------------------------------------------------------
insert into booking (booking_no, room_id, guest_name, guest_phone, start_time, end_time,
                    actual_check_in, actual_check_out, status, amount, created_by) values
  -- 6 天前，钟点房晚间
  ('D20260001', 1, '李文博', '138_0000_0001', date_sub(curdate(), interval 6 day) + interval 19 hour,
   date_sub(curdate(), interval 6 day) + interval 23 hour,
   date_sub(curdate(), interval 6 day) + interval 19 hour + interval 4 minute,
   date_sub(curdate(), interval 6 day) + interval 23 hour, 'CHECKED_OUT', 0.00, 1),
  ('D20260002', 5, '王思远', '138_0000_0002', date_sub(curdate(), interval 6 day) + interval 20 hour,
   date_sub(curdate(), interval 6 day) + interval 23 hour + interval 30 minute,
   date_sub(curdate(), interval 6 day) + interval 20 hour,
   date_sub(curdate(), interval 6 day) + interval 23 hour + interval 30 minute, 'CHECKED_OUT', 0.00, 2),
  -- 5 天前，跨零点全日房（营业日切割的用例）
  ('D20260003', 8, '张轶群', '138_0000_0003', date_sub(curdate(), interval 5 day) + interval 14 hour,
   date_sub(curdate(), interval 4 day) + interval 12 hour,
   date_sub(curdate(), interval 5 day) + interval 14 hour + interval 11 minute,
   date_sub(curdate(), interval 4 day) + interval 12 hour + interval 20 minute, 'CHECKED_OUT', 0.00, 1),
  ('D20260004', 2, '陈可欣', '138_0000_0004', date_sub(curdate(), interval 5 day) + interval 13 hour,
   date_sub(curdate(), interval 5 day) + interval 17 hour,
   date_sub(curdate(), interval 5 day) + interval 13 hour,
   date_sub(curdate(), interval 5 day) + interval 17 hour, 'CHECKED_OUT', 0.00, 2),
  -- 4 天前，白天冷时段
  ('D20260005', 3, '刘承宇', '138_0000_0005', date_sub(curdate(), interval 4 day) + interval 10 hour,
   date_sub(curdate(), interval 4 day) + interval 13 hour,
   date_sub(curdate(), interval 4 day) + interval 10 hour,
   date_sub(curdate(), interval 4 day) + interval 13 hour, 'CHECKED_OUT', 0.00, 1),
  ('D20260006', 14, '赵梦洁', '138_0000_0006', date_sub(curdate(), interval 4 day) + interval 14 hour,
   date_sub(curdate(), interval 3 day) + interval 12 hour,
   date_sub(curdate(), interval 4 day) + interval 14 hour,
   date_sub(curdate(), interval 3 day) + interval 12 hour, 'CHECKED_OUT', 0.00, 2),
  -- 3 天前，爽约取消（既不占位也不计营收）
  ('D20260007', 6, '孙志豪', '138_0000_0007', date_sub(curdate(), interval 3 day) + interval 21 hour,
   date_sub(curdate(), interval 3 day) + interval 23 hour + interval 30 minute,
   null, null, 'CANCELLED', 0.00, 2),
  ('D20260008', 9, '周雅静', '138_0000_0008', date_sub(curdate(), interval 3 day) + interval 19 hour,
   date_sub(curdate(), interval 3 day) + interval 22 hour,
   date_sub(curdate(), interval 3 day) + interval 19 hour,
   date_sub(curdate(), interval 3 day) + interval 22 hour, 'CHECKED_OUT', 0.00, 1),
  -- 2 天前
  ('D20260009', 10, '吴凯旋', '138_0000_0009', date_sub(curdate(), interval 2 day) + interval 20 hour,
   date_sub(curdate(), interval 1 day) + interval 12 hour,
   date_sub(curdate(), interval 2 day) + interval 20 hour,
   date_sub(curdate(), interval 1 day) + interval 12 hour, 'CHECKED_OUT', 0.00, 1),
  ('D20260010', 4, '郑晓彤', '138_0000_0010', date_sub(curdate(), interval 2 day) + interval 15 hour,
   date_sub(curdate(), interval 2 day) + interval 19 hour,
   date_sub(curdate(), interval 2 day) + interval 15 hour,
   date_sub(curdate(), interval 2 day) + interval 19 hour, 'CHECKED_OUT', 0.00, 2),
  -- 昨天，晚间密集（热力图高峰）
  ('D20260011', 1, '马若涵', '138_0000_0011', date_sub(curdate(), interval 1 day) + interval 18 hour,
   date_sub(curdate(), interval 1 day) + interval 22 hour,
   date_sub(curdate(), interval 1 day) + interval 18 hour,
   date_sub(curdate(), interval 1 day) + interval 22 hour, 'CHECKED_OUT', 0.00, 1),
  ('D20260012', 2, '胡泽宇', '138_0000_0012', date_sub(curdate(), interval 1 day) + interval 19 hour,
   date_sub(curdate(), interval 1 day) + interval 23 hour,
   date_sub(curdate(), interval 1 day) + interval 19 hour,
   date_sub(curdate(), interval 1 day) + interval 23 hour, 'CHECKED_OUT', 0.00, 1),
  ('D20260013', 5, '林雨薇', '138_0000_0013', date_sub(curdate(), interval 1 day) + interval 20 hour,
   date_sub(curdate(), interval 1 day) + interval 23 hour + interval 30 minute,
   date_sub(curdate(), interval 1 day) + interval 20 hour,
   date_sub(curdate(), interval 1 day) + interval 23 hour + interval 30 minute, 'CHECKED_OUT', 0.00, 2),
  ('D20260014', 15, '何书豪', '138_0000_0014', date_sub(curdate(), interval 1 day) + interval 14 hour,
   date_sub(curdate(), interval 0 day) + interval 12 hour,
   date_sub(curdate(), interval 1 day) + interval 14 hour,
   curdate() + interval 12 hour, 'CHECKED_OUT', 588.00, 2);

-- ---------------------------------------------------------------------------
-- 2) 今天在住 IN_USE：booking + occupancy 双行（占用还没释放，房态图上必须看得见）
--    block_start/block_end = 业务区间外再套 room_type 的清扫缓冲（总纲 B1）
-- ---------------------------------------------------------------------------
insert into booking (booking_no, room_id, guest_name, guest_phone, start_time, end_time,
                     actual_check_in, status, amount, created_by) values
  ('T20260101', 3, '许晨阳', '138_0000_0101', curdate() + interval 8 hour, curdate() + interval 12 hour,
   curdate() + interval 8 hour + interval 3 minute, 'IN_USE', 148.00, 1),
  ('T20260102', 11, '范可昕', '138_0000_0102', curdate() + interval 14 hour,
   date_add(curdate(), interval 1 day) + interval 12 hour,
   curdate() + interval 14 hour, 'IN_USE', 388.00, 1);

insert into occupancy (room_id, booking_id, start_time, end_time, block_start, block_end, kind)
select 3, b.id, b.start_time, b.end_time,
       date_sub(b.start_time, interval 0 minute), date_add(b.end_time, interval 30 minute), 'ROOM'
from booking b where b.booking_no = 'T20260101';

insert into occupancy (room_id, booking_id, start_time, end_time, block_start, block_end, kind)
select 11, b.id, b.start_time, b.end_time,
       date_sub(b.start_time, interval 15 minute), date_add(b.end_time, interval 60 minute), 'ROOM'
from booking b where b.booking_no = 'T20260102';

-- ---------------------------------------------------------------------------
-- 3) 未来已确认 CONFIRMED：同样要有占用行，否则可售查询会把已卖出的时段再卖一次
-- ---------------------------------------------------------------------------
insert into booking (booking_no, room_id, guest_name, guest_phone, start_time, end_time,
                     status, amount, created_by) values
  ('F20260201', 1, '邓凯文', '138_0000_0201', curdate() + interval 18 hour,
   curdate() + interval 22 hour, 'CONFIRMED', 148.00, 1),
  ('F20260202', 2, '曹素菲', '138_0000_0202', curdate() + interval 19 hour,
   curdate() + interval 23 hour + interval 30 minute, 'CONFIRMED', 168.00, 2),
  ('F20260203', 5, '彭于晏', '138_0000_0203', curdate() + interval 19 hour,
   curdate() + interval 23 hour, 'CONFIRMED', 218.00, 1),
  ('F20260204', 14, '苏明远', '138_0000_0204', curdate() + interval 14 hour,
   date_add(curdate(), interval 1 day) + interval 12 hour, 'CONFIRMED', 588.00, 2),
  ('F20260205', 6, '贾若琪', '138_0000_0205', date_add(curdate(), interval 1 day) + interval 20 hour,
   date_add(curdate(), interval 1 day) + interval 23 hour, 'CONFIRMED', 188.00, 1);

insert into occupancy (room_id, booking_id, start_time, end_time, block_start, block_end, kind)
select b.room_id, b.id, b.start_time, b.end_time,
       date_sub(b.start_time, interval case when rt.clean_before_minutes is null then 0
                                            else rt.clean_before_minutes end minute),
       date_add(b.end_time, interval rt.clean_after_minutes minute), 'ROOM'
from booking b
join room r on r.id = b.room_id
join room_type rt on rt.id = r.room_type_id
where b.booking_no in ('F20260201', 'F20260202', 'F20260203', 'F20260204', 'F20260205');

-- ---------------------------------------------------------------------------
-- 4) 一笔未确认的预占 HELD（30 分钟后过期，正好给超时回收任务当靶子）
-- ---------------------------------------------------------------------------
insert into booking (booking_no, room_id, guest_name, guest_phone, start_time, end_time,
                     status, hold_expire_at, amount, created_by) values
  ('H20260301', 7, '临时预占', null, curdate() + interval 21 hour, curdate() + interval 23 hour,
   'HELD', date_add(now(), interval 30 minute), 0.00, 2);

insert into occupancy (room_id, booking_id, start_time, end_time, block_start, block_end, kind)
select b.room_id, b.id, b.start_time, b.end_time, b.start_time,
       date_add(b.end_time, interval 45 minute), 'ROOM'
from booking b where b.booking_no = 'H20260301';

-- ---------------------------------------------------------------------------
-- 5) 全日房标识。sale_type 是"声明"不是"推断"（实施计划 2.5 第 2 条）：
--    W5 之前用"跨天就算全日房"倒推，被 22:00→次日 00:30（只有 2.5 小时）证伪 ——
--    所以这里逐条点名，绝不在 SQL 里写 `end_time 跨天 then FULL_DAY` 这种判断。
--    漏掉这一段的后果是实的：全日单会被当成钟点房计费，
--    起步只覆盖 3 小时，剩下的 19 小时全按超时单价收，账单能翻两三倍。
--
--    D20260009 原来做成 20:00 → 次日 12:00：既不是模板（全日房必须 14:00 入住）、
--    也不是合法钟点（不许跨天），是这条规则之前的遗留行，这里顺手改回模板。
-- ---------------------------------------------------------------------------
update booking
set start_time = date_sub(curdate(), interval 2 day) + interval 14 hour,
    actual_check_in = date_sub(curdate(), interval 2 day) + interval 14 hour,
    amount = 388.00
where booking_no = 'D20260009';

update booking set sale_type = 'FULL_DAY' where booking_no in
  ('D20260003', 'D20260006', 'D20260009', 'D20260014', 'T20260102', 'F20260204');

-- 自检：历史订单不该有房时间占用行；今天/未来的该有
-- 只数 ROOM —— 退房后留下的 CLEANING 行是正常状态（W5 的清扫闭环）
select '历史订单残留房占用行数（必须为 0）' as check_item, count(1) as n
from occupancy o join booking b on b.id = o.booking_id
where o.kind = 'ROOM'
  and b.status in ('SETTLED', 'CHECKED_OUT', 'CANCELLED')
union all
select '当前占用行数', count(1) from occupancy
union all
-- 编码自检用长度而不是打印中文：cp936 终端下中文本来就花，长度是终端无关的
select '李文博字符数（必须为 3；出现 9 说明客户端按 latin1 读了 UTF-8 文件）',
       char_length(guest_name)
from booking where booking_no = 'D20260001';
