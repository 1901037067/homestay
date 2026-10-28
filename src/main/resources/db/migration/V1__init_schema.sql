-- ============================================================================
-- V1__init_schema.sql
-- 民宿分时预订系统 —— 初始结构
--
-- 两条总纲（冲突检测与释放语义由它推出，见 实施计划 2.3）：
--   A. occupancy 有行即占位；取消 / 超时 / 退房 = 在同一事务同一把房间行锁内 DELETE。
--   B. 清扫缓冲主线折进 block_start/block_end（B1）；只有做"清扫工单"加分项时才改用
--      kind=CLEANING 独立行（B2），且此时必须把 clean_*_minutes 置 0，避免缓冲算两遍。
--
-- 所有表显式声明 utf8mb4：答辩机若用 phpStudy 的 MySQL 5.7，默认字符集是 utf8mb3，
-- 中文客人姓名会存不进去。
-- ============================================================================

create table sys_user (
  id            bigint primary key auto_increment,
  username      varchar(64)  not null unique,
  password_hash varchar(128) not null,
  display_name  varchar(64)  not null,
  enabled       tinyint(1)   not null default 1
) engine innodb default character set utf8mb4;

create table property (
  id           bigint primary key auto_increment,
  name         varchar(128) not null,
  address      varchar(255) null,
  day_cut_time time not null default '06:00:00' comment '营业日切割点：此刻之前算前一天',
  open_time    time not null default '08:00:00' comment '仅约束钟点房，全日房模板豁免',
  close_time   time not null default '22:00:00' comment '仅约束钟点房，全日房模板豁免',
  created_at   datetime not null default current_timestamp,
  updated_at   datetime not null default current_timestamp on update current_timestamp
) engine innodb default character set utf8mb4;

create table room_type (
  id                   bigint primary key auto_increment,
  property_id          bigint not null,
  name                 varchar(64) not null,
  base_price           decimal(10,2) not null,
  min_hours            int not null default 3 comment '起步时长，不约束全日房',
  extra_hour_price     decimal(10,2) not null comment '超时单价',
  clean_before_minutes int not null default 0 comment '总纲 B1 才生效',
  clean_after_minutes  int not null default 45 comment '总纲 B1 才生效',
  full_day_start       time not null default '14:00:00',
  full_day_end         time not null default '12:00:00' comment '次日',
  unique key uk_type_name (property_id, name),
  constraint fk_type_property foreign key (property_id) references property(id)
) engine innodb default character set utf8mb4;

create table room (
  id           bigint primary key auto_increment,
  property_id  bigint not null,
  room_type_id bigint not null,
  room_no      varchar(32) not null,
  floor_no     int null,
  status       varchar(16) not null default 'ON_SALE' comment 'ON_SALE / MAINTENANCE / OFFLINE',
  created_at   datetime not null default current_timestamp,
  updated_at   datetime not null default current_timestamp on update current_timestamp,
  unique key uk_room_no (property_id, room_no),
  key idx_room_type (room_type_id),
  constraint fk_room_property foreign key (property_id)   references property(id),
  constraint fk_room_type     foreign key (room_type_id)  references room_type(id)
) engine innodb default character set utf8mb4;

create table booking (
  id               bigint primary key auto_increment,
  booking_no       varchar(32) not null unique comment '幂等键',
  room_id          bigint not null,
  guest_name       varchar(64) not null,
  guest_phone      varchar(32) null,
  start_time       datetime not null,
  end_time         datetime not null,
  actual_check_in  datetime null,
  actual_check_out datetime null,
  status           varchar(24) not null comment 'HELD/CONFIRMED/IN_USE/CHECKED_OUT/SETTLED/CANCELLED/RELEASED',
  hold_expire_at   datetime null,
  amount           decimal(10,2) not null default 0,
  remark           varchar(255) null,
  created_by       bigint null,
  created_at       datetime not null default current_timestamp,
  updated_at       datetime not null default current_timestamp on update current_timestamp,
  key idx_room_time    (room_id, start_time, end_time),
  key idx_status_start (status, start_time),
  key idx_start_time   (start_time),
  constraint fk_booking_room foreign key (room_id) references room(id)
) engine innodb default character set utf8mb4;

-- 真正参与冲突检测的表。总纲 A：有行即占位，释放即删除，不看任何状态字段。
create table occupancy (
  id          bigint primary key auto_increment,
  room_id     bigint not null,
  booking_id  bigint not null,
  start_time  datetime not null comment '业务时间（客人看到的）',
  end_time    datetime not null,
  block_start datetime not null comment '含清扫缓冲，冲突检测打在这对字段上',
  block_end   datetime not null,
  kind        varchar(16) not null comment 'ROOM / CLEANING（CLEANING 属加分项 B2）',
  created_at  datetime not null default current_timestamp,
  -- 一个 booking 可同时有 ROOM 行与（B2 模式下的）CLEANING 行，唯一键必须带 kind。
  unique key uk_booking_kind (booking_id, kind),
  key idx_block (room_id, block_start, block_end),
  constraint fk_occ_room    foreign key (room_id)    references room(id),
  constraint fk_occ_booking foreign key (booking_id) references booking(id)
) engine innodb default character set utf8mb4;
