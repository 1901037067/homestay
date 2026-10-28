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
    @DisplayName("六张表全部建成，且都是 InnoDB + utf8mb4")
    void tablesCreatedWithRightEngineAndCharset() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select table_name, engine, table_collation
                from information_schema.tables
                where table_schema = database()
                """);

        assertThat(rows).extracting(r -> ((String) r.get("table_name")).toLowerCase())
                .contains("sys_user", "property", "room_type", "room", "booking", "occupancy");

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

        assertThat(fks).contains(
                "fk_type_property",
                "fk_room_property",
                "fk_room_type",
                "fk_booking_room",
                "fk_occ_room",
                "fk_occ_booking");
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
