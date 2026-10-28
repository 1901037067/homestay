package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 结构即契约：把 DDL 里最关键的设计点变成断言，而不只是"迁移没报错"。
 * 这几条一旦被人改坏，CI 立刻红，比事后查冲突检测失效便宜得多。
 */
class FlywaySchemaIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("九张表全部建成，且都是 InnoDB + utf8mb4")
    void tablesCreatedWithRightEngineAndCharset() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select table_name, engine, table_collation
                from information_schema.tables
                where table_schema = database()
                  and table_name <> 'flyway_schema_history'   -- Flyway 自己的账本，不是业务表
                """);

        // 用"精确集合"而不是 contains：contains 只保证"至少这些"，operation_log 整张表被删了
        // 也照样绿 —— 结构契约一旦允许缺项，它就不再是契约
        assertThat(rows).extracting(r -> ((String) r.get("table_name")).toLowerCase())
                .containsExactlyInAnyOrder("sys_user", "property", "room_type", "room", "booking",
                        "occupancy", "rate_rule", "bill", "operation_log");

        assertThat(rows).allSatisfy(r -> {
            assertThat((String) r.get("engine")).isEqualTo("InnoDB");
            assertThat((String) r.get("table_collation")).startsWith("utf8mb4");
        });
    }

    @Test
    @DisplayName("uk_booking_kind 必须是 (booking_id, kind) 两列 —— 单列会让清扫工单插不进去")
    void bookingKindUniqueKeyHasTwoColumns() {
        List<String> cols = jdbc.queryForList("""
                select column_name
                from information_schema.key_column_usage
                where table_schema = database()
                  and table_name = 'occupancy'
                  and constraint_name = 'uk_booking_kind'
                order by ordinal_position
                """, String.class);

        assertThat(cols).containsExactly("booking_id", "kind");
    }

    @Test
    @DisplayName("外键齐备：挡住『删了房间、账单指向幽灵房间』")
    void foreignKeysExist() {
        List<String> fks = jdbc.queryForList("""
                select constraint_name
                from information_schema.referential_constraints
                where constraint_schema = database()
                """, String.class);

        // 同样精确比：漏一个外键（比如 fk_rule_type）不该因为"其它都还在"而绿
        assertThat(fks).containsExactlyInAnyOrder(
                "fk_type_property",
                "fk_room_property",
                "fk_room_type",
                "fk_booking_room",
                "fk_occ_room",
                "fk_occ_booking",
                "fk_rule_property",
                "fk_rule_type",
                "fk_bill_booking");
    }

    @Test
    @DisplayName("uk_booking_bill 存在 —— 一张单只有一张账单，重复结算靠它兜底")
    void billHasUniqueBookingKey() {
        List<String> cols = jdbc.queryForList("""
                select column_name
                from information_schema.key_column_usage
                where table_schema = database()
                  and table_name = 'bill'
                  and constraint_name = 'uk_booking_bill'
                """, String.class);

        assertThat(cols).containsExactly("booking_id");
    }

    @Test
    @DisplayName("冲突检测用的索引 (room_id, block_start, block_end) 存在")
    void overlapIndexExists() {
        List<String> cols = jdbc.queryForList("""
                select column_name
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'occupancy'
                  and index_name = 'idx_block'
                order by seq_in_index
                """, String.class);

        assertThat(cols).containsExactly("room_id", "block_start", "block_end");
    }

    @Test
    @DisplayName("LocalDateTime 经 JDBC 真正写入再读回，不发生时区位移")
    void localDateTimeSurvivesARealRoundTrip() {
        // 之前的版本只比对了 SQL 字面量与服务器偏移，没走驱动的绑定/取回路径 ——
        // 而 preserveInstants=false 这类参数影响的恰恰是那条路径。这里真的插一行再读回来
        jdbc.execute("create temporary table tz_probe (id int primary key, at datetime not null)");
        java.time.LocalDateTime written = java.time.LocalDateTime.of(2026, 11, 5, 23, 30, 15);
        jdbc.update("insert into tz_probe (id, at) values (?, ?)", 1, written);

        java.time.LocalDateTime read = jdbc.queryForObject(
                "select at from tz_probe where id = 1", java.time.LocalDateTime.class);
        assertThat(read).as("跨零点时刻写进去再读出来必须一模一样").isEqualTo(written);
    }

    @Test
    @DisplayName("会话时区跟随服务端，DATETIME 不被隐式换算")
    void datetimeRoundTripsWithoutShift() {
        String written = jdbc.queryForObject(
                "select date_format('2026-11-05 23:30:00', '%Y-%m-%d %H:%i:%s')", String.class);
        assertThat(written).isEqualTo("2026-11-05 23:30:00");

        Integer offsetHours = jdbc.queryForObject(
                "select (time_to_sec(timediff(now(), utc_timestamp())) div 3600)", Integer.class);
        assertThat(offsetHours).isEqualTo(8);
    }
}
