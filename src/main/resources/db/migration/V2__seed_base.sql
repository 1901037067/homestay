-- ============================================================================
-- V2__seed_base.sql —— 结构性种子数据（系统运行前提，随迁移走）
--
-- 演示流水不放这里（实施计划第 6 节）：假订单在 db/demo/seed_demo.sql，手动 source。
-- 否则每次全新部署自带一年历史，答辩时"这些数据哪来的"很难答。
--
-- 账号密码：admin / staff 均为 Admin@123
-- 哈希由项目自身的 BCryptPasswordEncoder 生成，并由 SeedPasswordHashTest 断言可互认。
-- 换密码：mvn test -Dtest=SeedPasswordHashTest，读 target/seed-admin-hash.txt 再粘回来。
-- ============================================================================

insert into sys_user (id, username, password_hash, display_name, enabled) values
  (1, 'admin', '$2a$10$/j7MN36lPm1DE/FAk979hufLsnTvqLQnOm.xiX4u90wzTr5U5KvZ.', '店长', 1),
  (2, 'staff', '$2a$10$/j7MN36lPm1DE/FAk979hufLsnTvqLQnOm.xiX4u90wzTr5U5KvZ.', '前台小张', 1);

-- 两家店：day_cut_time 故意不同，报表按营业日切割时才看得出差异
insert into property (id, name, address, day_cut_time, open_time, close_time) values
  (1, '云栖民宿', '杭州市西湖区龙井路 18 号', '06:00:00', '08:00:00', '22:00:00'),
  (2, '山语民宿', '杭州市拱墅区运河东路 7 号', '04:00:00', '09:00:00', '21:00:00');

-- 4 个房型：纯钟点 / 纯钟点(大床) / 纯全日 / 混合（既能按全日卖也能拆钟点）
insert into room_type
  (id, property_id, name, base_price, min_hours, extra_hour_price,
   clean_before_minutes, clean_after_minutes, full_day_start, full_day_end) values
  (1, 1, '钟点房·单人',  68.00,  3, 20.00,  0, 30, '14:00:00', '12:00:00'),
  (2, 1, '钟点房·大床',  98.00,  3, 28.00,  0, 45, '14:00:00', '12:00:00'),
  (3, 1, '全日房·家庭房', 388.00, 3, 40.00, 15, 60, '14:00:00', '12:00:00'),
  (4, 2, '全日房·江景套房', 588.00, 3, 60.00, 15, 90, '14:00:00', '12:00:00');

-- 18 间房：云栖 13 间（1F 钟点、2F 家庭），山语 5 间套房
insert into room (id, property_id, room_type_id, room_no, floor_no, status) values
  (1,  1, 1, '101', 1, 'ON_SALE'),
  (2,  1, 1, '102', 1, 'ON_SALE'),
  (3,  1, 1, '103', 1, 'ON_SALE'),
  (4,  1, 1, '104', 1, 'ON_SALE'),
  (5,  1, 2, '105', 1, 'ON_SALE'),
  (6,  1, 2, '106', 1, 'ON_SALE'),
  (7,  1, 2, '107', 1, 'ON_SALE'),
  (8,  1, 3, '201', 2, 'ON_SALE'),
  (9,  1, 3, '202', 2, 'ON_SALE'),
  (10, 1, 3, '203', 2, 'ON_SALE'),
  (11, 1, 3, '204', 2, 'ON_SALE'),
  (12, 1, 3, '205', 2, 'ON_SALE'),
  (13, 1, 2, '108', 1, 'MAINTENANCE'),
  (14, 2, 4, '301', 3, 'ON_SALE'),
  (15, 2, 4, '302', 3, 'ON_SALE'),
  (16, 2, 4, '303', 3, 'ON_SALE'),
  (17, 2, 4, '305', 3, 'ON_SALE'),
  (18, 2, 4, '306', 3, 'OFFLINE');
