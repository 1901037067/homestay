package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Room;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.PropertyVO;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 报表接口（W7）。金额归属的算术已经在 {@code StatsAggregatorTest} 里用纯函数钉住了，
 * 这一层只验<b>只有连上真库才会暴露的东西</b>：SQL 扫出来的行对不对、
 * 日期区间与切割点在真实数据上是否一致、CSV 的 BOM 与合计行、以及民宿隔离。
 *
 * <p>订单行直接用 JDBC 造：报表只读 {@code booking} 的几列，走完整下单流程
 * 会让一个报表测试依赖计费与清扫的一堆前提，测的东西反而变模糊。造行时把
 * 状态、金额、实际退房时刻都写全，正好覆盖"Settled / 已退房待结算 / 取消"三档。
 */
@AutoConfigureMockMvc
class StatsIT extends AbstractMysqlIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;

    private PropertyVO property;
    private Long roomA;
    private Long roomB;

    @BeforeEach
    void fixture() {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        property = propertyService.list().stream().filter(p -> p.name().equals("店" + tag))
                .findFirst().orElseThrow();
        roomTypeService.save(new RoomTypeReq(null, property.id(), "型" + tag, new BigDecimal("100.00"),
                1, new BigDecimal("20.00"), null, 0, 30, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long typeId = roomTypeService.list(property.id()).get(0).id();
        roomA = roomService.save(new RoomReq(null, property.id(), typeId, "A" + tag, 1, Room.ON_SALE)).getId();
        roomB = roomService.save(new RoomReq(null, property.id(), typeId, "B" + tag, 1, Room.ON_SALE)).getId();
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("区间内每天都有点位，分母是在售房间数，营收只认已结算、待结算单列")
    void dailySeriesAndTotals() throws Exception {
        // 11-10：一间房已结算 148、一间房已退房未结算 188
        insert(roomA, "SETTLED", "2026-11-10T10:00", "2026-11-10T13:00", "2026-11-10T13:00", 148);
        insert(roomB, "CHECKED_OUT", "2026-11-10T14:00", "2026-11-10T17:00", "2026-11-10T17:00", 188);
        // 11-11：一笔取消，什么都不该算
        insert(roomA, "CANCELLED", "2026-11-11T10:00", "2026-11-11T13:00", null, 0);

        JsonNode d = report("2026-11-10", "2026-11-11").path("data");
        assertThat(d.path("daily")).hasSize(2);
        assertThat(d.path("sellableRooms").asInt()).isEqualTo(2);
        assertThat(d.path("totals").path("revenue").decimalValue()).isEqualByComparingTo("148");
        assertThat(d.path("totals").path("pendingAmount").decimalValue()).isEqualByComparingTo("188");
        // 分母是 2 间房 × 2 天 = 4 个房晚；11-10 卖了两间、11-11 零间 → 2/4 = 50%
        assertThat(d.path("totals").path("avgOccupancy").asDouble()).isEqualTo(50.0);
        assertThat(d.path("daily").get(1).path("bookings").asInt()).as("取消单不计单数").isZero();
        assertThat(d.path("daily").get(1).path("soldRooms").asInt()).isZero();
    }

    @Test
    @DisplayName("营业日切割落在 SQL 出数上：05:00 的单属于前一天，只查后一天时不能出现")
    void businessDayCutAppliesToQueriedRange() throws Exception {
        insert(roomA, "SETTLED", "2026-11-11T05:00", "2026-11-11T08:00", "2026-11-11T08:00", 120);

        // 06:00 切割 → 这笔算 11-10
        JsonNode prev = report("2026-11-10", "2026-11-10").path("data");
        assertThat(prev.path("daily").get(0).path("soldRooms").asInt()).isEqualTo(1);
        assertThat(prev.path("totals").path("revenue").decimalValue()).isEqualByComparingTo("120");

        // 只查 11-11 时它不该出现（同一笔单不能被两天各算一次）
        JsonNode next = report("2026-11-11", "2026-11-11").path("data");
        assertThat(next.path("daily").get(0).path("soldRooms").asInt()).isZero();
        assertThat(next.path("totals").path("revenue").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("热度横轴从切割点起转：凌晨 2 点的单落在第 20 格、小时标签是 2")
    void heatAxisIsRotated() throws Exception {
        insert(roomA, "SETTLED", "2026-11-11T02:00", "2026-11-11T05:00", "2026-11-11T05:00", 88);

        JsonNode heat = report("2026-11-10", "2026-11-10").path("data").path("heat");
        assertThat(heat).hasSize(3);
        assertThat(heat.get(0).path("businessDay").asText()).isEqualTo("2026-11-10");
        assertThat(heat.get(0).path("slot").asInt()).isEqualTo(20);
        assertThat(heat.get(0).path("hour").asInt()).isEqualTo(2);
        assertThat(heat.get(0).path("occupiedRooms").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("排行按房间营收降序，且只统计区间内的单")
    void rankingIsScopedToRange() throws Exception {
        insert(roomA, "SETTLED", "2026-11-10T10:00", "2026-11-10T13:00", null, 300);
        insert(roomB, "SETTLED", "2026-11-10T14:00", "2026-11-10T17:00", null, 100);
        // 区间外的单不能进排行
        insert(roomB, "SETTLED", "2026-11-20T14:00", "2026-11-20T17:00", null, 999);

        JsonNode ranking = report("2026-11-10", "2026-11-10").path("data").path("ranking");
        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).path("roomId").asLong()).isEqualTo(roomA);
        assertThat(ranking.get(0).path("revenue").decimalValue()).isEqualByComparingTo("300");
        assertThat(ranking.get(1).path("revenue").decimalValue()).as("区间外的 999 不能混进来")
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("民宿之间互相隔离：查 A 店不会看到 B 店的单")
    void propertyIsolation() throws Exception {
        insert(roomA, "SETTLED", "2026-11-10T10:00", "2026-11-10T13:00", null, 300);

        // 另开一家店，也放一间房
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "别店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        PropertyVO other = propertyService.list().stream().filter(p -> p.name().equals("别店" + tag))
                .findFirst().orElseThrow();

        JsonNode d = report(other.id(), "2026-11-10", "2026-11-10").path("data");
        assertThat(d.path("totals").path("revenue").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.path("ranking")).isEmpty();
    }

    @Test
    @DisplayName("CSV 带 BOM、含表头与合计行，Excel 双击不乱码")
    void exportCsvHasBomAndTotalsRow() throws Exception {
        insert(roomA, "SETTLED", "2026-11-10T10:00", "2026-11-10T13:00", null, 148);

        byte[] body = mockMvc.perform(get("/api/stats/export")
                        .session(login())
                        .param("propertyId", String.valueOf(property.id()))
                        .param("from", "2026-11-10")
                        .param("to", "2026-11-10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).as("没有 BOM 的话 Excel 会按 GBK 读，中文全乱").startsWith("\uFEFF");
        assertThat(csv).contains("营业日,可售房间,售出房间,出租率%,已结算营收");
        assertThat(csv).contains("2026-11-10,2,1,50.00,148.00,0.00,1,0,0");
        assertThat(csv).contains("合计,2,1,50.00,148.00,0.00,1,0,0");
        assertThat(csv).contains("day_cut_time");
    }

    // ------------------------------------------------------------------ 夹具

    /** 直接造一行订单：报表只读 booking 的这几列，造行比走完整流程更能聚焦。 */
    private void insert(Long roomId, String status, String start, String end, String actualOut, int amount) {
        jdbc.update("""
                insert into booking (booking_no, sale_type, room_id, guest_name, start_time, end_time,
                                     actual_check_out, status, amount)
                values (?, 'HOURLY', ?, '报表客人', ?, ?, ?, ?, ?)
                """, "S" + System.nanoTime(), roomId, start, end, actualOut, status,
                new BigDecimal(amount));
    }

    private JsonNode report(String from, String to) throws Exception {
        return report(property.id(), from, to);
    }

    private JsonNode report(Long propertyId, String from, String to) throws Exception {
        String body = mockMvc.perform(get("/api/stats/report")
                        .session(login())
                        .param("propertyId", String.valueOf(propertyId))
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(body);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
