-- ============================================================================
-- V4__operation_log.sql
--
-- 审计表：谁在什么时候把哪个对象从什么改成了什么。
-- before/after 存 JSON 文本而不是拆成一堆列 —— 被改的实体有六七个、字段各不相同，
-- 拆列会让这张表谁都不好写。毕设阶段够用，论文里 E-R 图上也画得下。
--
-- username 冗余存一份（而不是只留 user_id 外键）：账号被停用或改名后，
-- 历史日志仍要能读出"当时是谁"。审计表的这一列不参与后续业务查询。
-- ============================================================================

create table operation_log (
  id          bigint primary key auto_increment,
  user_id     bigint       null,
  username    varchar(64)  null,
  action      varchar(32)  not null comment 'BOOKING_CREATE / CHECK_IN / CHECK_OUT / EXTEND / RESCHEDULE / CANCEL / CONFIRM / CLEANING_DONE / ROOM_STATUS ...',
  entity      varchar(32)  not null comment 'booking / room / room_type / property',
  entity_id   bigint       null,
  summary     varchar(255) null comment '人话描述，日志列表直接显示这一列',
  before_json text         null,
  after_json  text         null,
  created_at  datetime not null default current_timestamp,
  key idx_log_time (created_at),
  key idx_log_entity (entity, entity_id),
  key idx_log_user (user_id)
) engine innodb default character set utf8mb4;
