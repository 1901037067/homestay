package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RateRuleReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.OperationLog;
import com.school.bnb.entity.Room;
import com.school.bnb.service.OperationLogService;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RateRuleService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.PropertyVO;
import org.springframework.mock.web.MockHttpSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 操作日志（W5）。走 MockMvc + 真实 session，而不是直接调 service ——
 * 因为"谁做的"这一列来自登录拦截器写入的 AuthContext，
 * 绕过 HTTP 层就测不到那条链路，日志里的 username 会一路是 null 而没人发现。
 */
@AutoConfigureMockMvc
class OperationLogIT extends AbstractMysqlIT {

    /**
     * 用"今天整天"的窗口：入住接口用的是 {@code now()}，而"到店时刻已过预订结束时刻"会被拒
     * （那条规则见 OccupancyTruthIT ⑩）。00:00–23:59 能把任意时刻的 now() 包进去。
     */
    private static final LocalDateTime DAY = LocalDateTime.now().toLocalDate().atStartOfDay();

    @Autowired MockMvc mockMvc;
    @Autowired OperationLogService logService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired RateRuleService rateRuleService;

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private Room room() {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        PropertyVO p = propertyService.list().stream().filter(x -> x.name().equals("店" + tag))
                .findFirst().orElseThrow();
        roomTypeService.save(new RoomTypeReq(null, p.id(), "型" + tag, new BigDecimal("100.00"),
                2, new BigDecimal("20.00"), null, 0, 30, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(p.id()).get(0).id();
        return roomService.save(new RoomReq(null, p.id(), tid, "R" + tag, 1, Room.ON_SALE));
    }

    private long createBooking(MockHttpSession session, Room room) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":" + room.getId() + ",\"guestName\":\"日志客人\","
                                + "\"startTime\":\"" + DAY.withHour(0) + "\","
                                + "\"endTime\":\"" + DAY.withHour(23).withMinute(59) + "\","
                                + "\"saleType\":\"HOURLY\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // 别用正则抠 "id": —— 响应里还有 roomId / propertyId，贪婪匹配会抓到别的字段
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("id").asLong();
    }

    private List<String> actionsOf(Long bookingId) {
        return logService.page(1, 50, null, "booking", bookingId, null).getRecords().stream()
                .map(OperationLog::getAction).toList();
    }

    @Test
    @DisplayName("一次完整流转留下五条日志，且记下了操作人")
    void lifecycleIsAuditedWithActor() throws Exception {
        MockHttpSession session = login();
        Room room = room();
        long id = createBooking(session, room);

        mockMvc.perform(post("/api/bookings/" + id + "/check-in").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/bookings/" + id + "/check-out").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/rooms/" + room.getId() + "/cleaning-done").session(session))
                .andExpect(status().isOk());

        assertThat(actionsOf(id)).contains("BOOKING_CREATE", "BOOKING_CHECK_IN", "BOOKING_CHECK_OUT");

        List<OperationLog> rows = logService.page(1, 50, null, "booking", id, null).getRecords();
        assertThat(rows).allSatisfy(r -> assertThat(r.getUsername()).isEqualTo("admin"));
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getAction()).isEqualTo("BOOKING_CHECK_OUT");
            assertThat(r.getBeforeJson()).contains("IN_USE");
            assertThat(r.getAfterJson()).contains("CHECKED_OUT");
        });
    }

    @Test
    @DisplayName("前后值必须是两份快照 —— 记同一个引用会让审计白记")
    void beforeAndAfterDiffer() throws Exception {
        MockHttpSession session = login();
        Room room = room();
        long id = createBooking(session, room);
        mockMvc.perform(post("/api/bookings/" + id + "/check-in").session(session)).andExpect(status().isOk());

        OperationLog row = logService.page(1, 50, "BOOKING_CHECK_IN", "booking", id, null)
                .getRecords().get(0);
        assertThat(row.getBeforeJson()).contains("CONFIRMED").doesNotContain("IN_USE");
        assertThat(row.getAfterJson()).contains("IN_USE");
    }

    @Test
    @DisplayName("房间上下架也进日志，且能按实体查")
    void roomStatusIsAudited() throws Exception {
        MockHttpSession session = login();
        Room room = room();
        // 必须走 HTTP：username 是登录拦截器写进 AuthContext 的，
        // 直接调 roomService 就绕过拦截器，日志里的操作人永远是 null 而没人发现
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/rooms/" + room.getId() + "/status")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isOk());

        List<OperationLog> rows = logService.page(1, 50, "ROOM_STATUS", "room", room.getId(), null).getRecords();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSummary()).contains("OFFLINE");
        assertThat(rows.get(0).getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("取消订单也留痕，便于事后追问是谁放掉的房")
    void cancelIsAudited() throws Exception {
        MockHttpSession session = login();
        Room room = room();
        long id = createBooking(session, room);
        mockMvc.perform(post("/api/bookings/" + id + "/cancel").session(session)).andExpect(status().isOk());

        assertThat(actionsOf(id)).contains("BOOKING_CANCEL");
    }

    @Test
    @DisplayName("改价与价格规则也要留痕 —— 它们直接决定账单金额")
    void priceChangesAreAudited() throws Exception {
        MockHttpSession session = login();
        Room room = room();
        Long typeId = room.getRoomTypeId();
        var type = roomTypeService.require(typeId);

        // 1) 改房型价格：摘要要能一眼看出变的是哪一项，前后值要留两份快照。
        //    必须走 HTTP —— 操作人来自登录拦截器写进 AuthContext 的值，
        //    直接调 roomTypeService.save 会拿到 username=null（这个坑本类开头就写着，我又踩了一次）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/room-types/" + typeId)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyId\":" + type.getPropertyId()
                                + ",\"name\":\"" + type.getName() + "\""
                                + ",\"basePrice\":218.00"
                                + ",\"minHours\":" + type.getMinHours()
                                + ",\"extraHourPrice\":" + type.getExtraHourPrice()
                                + ",\"cleanBeforeMinutes\":" + type.getCleanBeforeMinutes()
                                + ",\"cleanAfterMinutes\":" + type.getCleanAfterMinutes()
                                + ",\"fullDayStart\":\"" + type.getFullDayStart() + "\""
                                + ",\"fullDayEnd\":\"" + type.getFullDayEnd() + "\"}"))
                .andExpect(status().isOk());

        OperationLog price = logService.page(1, 20, "ROOM_TYPE_UPDATE", "room_type", typeId, null)
                .getRecords().get(0);
        assertThat(price.getUsername()).as("走 HTTP 的登录态才有操作人").isEqualTo("admin");
        assertThat(price.getSummary()).contains("基础价").contains("100.00").contains("218.00");
        assertThat(price.getBeforeJson()).contains("100.00");
        assertThat(price.getAfterJson()).contains("218.00");

        // 2) 新建价格规则同样留痕：这条以后每次试算都会读到它
        rateRuleService.save(new RateRuleReq(null, type.getPropertyId(), null, "周末上浮",
                null, null, List.of(6, 7), null, null, "MULTIPLY", new BigDecimal("1.20"), "ALL", 100, true));

        OperationLog ruleLog = logService.page(1, 20, "RATE_RULE_CREATE", "rate_rule", null, null)
                .getRecords().get(0);
        assertThat(ruleLog.getSummary()).contains("周末上浮").contains("×1.20").contains("房费+超时");
    }
}
