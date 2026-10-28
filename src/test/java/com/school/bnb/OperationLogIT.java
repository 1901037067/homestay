package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.OperationLog;
import com.school.bnb.entity.Room;
import com.school.bnb.service.OperationLogService;
import com.school.bnb.service.PropertyService;
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

    private static final LocalDateTime DAY = LocalDateTime.now().minusDays(1).toLocalDate().atStartOfDay();

    @Autowired MockMvc mockMvc;
    @Autowired OperationLogService logService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;

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
                2, new BigDecimal("20.00"), 0, 30, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(p.id()).get(0).id();
        return roomService.save(new RoomReq(null, p.id(), tid, "R" + tag, 1, Room.ON_SALE));
    }

    private long createBooking(MockHttpSession session, Room room, int from, int to) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":" + room.getId() + ",\"guestName\":\"日志客人\","
                                + "\"startTime\":\"" + DAY.withHour(from) + ":00\","
                                + "\"endTime\":\"" + DAY.withHour(to) + ":00\","
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
        long id = createBooking(session, room, 9, 12);

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
        long id = createBooking(session, room, 9, 12);
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
        long id = createBooking(session, room, 9, 12);
        mockMvc.perform(post("/api/bookings/" + id + "/cancel").session(session)).andExpect(status().isOk());

        assertThat(actionsOf(id)).contains("BOOKING_CANCEL");
    }
}
