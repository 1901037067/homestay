package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RateRuleReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Bill;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.mapper.BillMapper;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RateRuleService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.PropertyVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 计费与账单快照（W6）。
 *
 * <p>金额算术本身不在这里测 —— 那是 {@code PriceCalculatorTest} 三十条表驱动用例的活，
 * 纯函数不需要容器。这一层要测的是<b>只有连上真库才会暴露的东西</b>：
 * 快照到底存没存住、改了价历史账单会不会跟着变、结算守不守状态机、
 * 明细 JSON 能不能原样读回来、订单上的 amount 和账单的合计是不是同一个数。
 *
 * <p>全程走 MockMvc + 真实 session：结算要写"谁收的款"，而操作人来自登录拦截器，
 * 直接调 service 那条链就测不到（这个坑在 {@code OperationLogIT} 踩过一次）。
 */
@AutoConfigureMockMvc
class BillingIT extends AbstractMysqlIT {

    /** 用昨天做基准日：既保证入住时刻落在可售窗口内，也让"退房时刻=now"必然是超时。 */
    private static final LocalDateTime DAY = LocalDateTime.now().minusDays(1).toLocalDate().atStartOfDay();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BillMapper billMapper;
    @Autowired BookingMapper bookingMapper;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired RateRuleService rateRuleService;

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("试算与结算金额一致，订单 amount 与账单合计同源，明细读得回来")
    void previewEqualsSettledBill() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);

        // 起步 2 小时 100 元 + 超时 1 小时 20 元
        JsonNode preview = json(post("/api/bookings/" + id + "/bill/preview").session(session));
        assertThat(preview.path("data").path("snapshot").asBoolean()).isFalse();
        assertThat(preview.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(preview.path("data").path("lines")).hasSize(2);
        assertThat(preview.path("data").path("notice").asText("")).contains("尚未退房");

        checkOutAt(session, id, DAY.withHour(12));
        JsonNode settled = json(post("/api/bookings/" + id + "/settle").session(session));
        assertThat(settled.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(settled.path("data").path("snapshot").asBoolean()).isTrue();

        Booking b = bookingMapper.selectById(id);
        assertThat(b.getStatus()).isEqualTo(Booking.SETTLED);
        assertThat(b.getAmount()).isEqualByComparingTo("120.00");

        // 快照读回来必须和当时算出来的一模一样，包括每条明细的金额
        JsonNode view = json(get("/api/bookings/" + id + "/bill").session(session));
        assertThat(view.path("data").path("snapshot").asBoolean()).isTrue();
        assertThat(view.path("data").path("lines")).hasSize(2);
        BigDecimal printed = new BigDecimal("0");
        for (JsonNode line : view.path("data").path("lines")) {
            printed = printed.add(line.path("amount").decimalValue());
        }
        assertThat(printed).isEqualByComparingTo(view.path("data").path("totalAmount").decimalValue());
    }

    @Test
    @DisplayName("改了价不回溯已结算账单 —— 这正是存快照的全部理由")
    void rateChangeDoesNotRewriteHistory() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);
        checkOutAt(session, id, DAY.withHour(12));
        json(post("/api/bookings/" + id + "/settle").session(session));

        Bill before = billMapper.findByBookingId(id);
        assertThat(before.getTotalAmount()).isEqualByComparingTo("120.00");

        // 把房型价整体翻倍（走 service，等价于后台改了价）
        Long typeId = room.getRoomTypeId();
        var type = roomTypeService.require(typeId);
        roomTypeService.save(new RoomTypeReq(typeId, type.getPropertyId(), type.getName(),
                new BigDecimal("500.00"), type.getMinHours(), new BigDecimal("90.00"),
                type.getChargeUnitMinutes(), type.getCleanBeforeMinutes(), type.getCleanAfterMinutes(),
                type.getFullDayStart(), type.getFullDayEnd()));

        JsonNode view = json(get("/api/bookings/" + id + "/bill").session(session));
        assertThat(view.path("data").path("snapshot").asBoolean()).isTrue();
        assertThat(view.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("120.00")
                .as("历史账单不能因为改价而变化");

        // 而新开一单必须用新价：证明"不回溯"不是因为价格被缓存住了
        long another = booking(session, room, 14, 17);
        JsonNode fresh = json(post("/api/bookings/" + another + "/bill/preview").session(session));
        assertThat(fresh.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("590.00");
    }

    @Test
    @DisplayName("价格规则命中后改写试算金额，并把规则名写进账单快照")
    void ruleAppliesToPreviewAndIsRecordedOnTheBill() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);

        // 整家民宿上浮 1.5 倍：起步 150 + 超时 30 = 180
        rateRuleService.save(new RateRuleReq(null, room.getPropertyId(), null, "全场上浮",
                null, null, List.of(), null, null, "MULTIPLY", new BigDecimal("1.50"), 100, true));

        JsonNode preview = json(post("/api/bookings/" + id + "/bill/preview").session(session));
        assertThat(preview.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(preview.path("data").path("lines").get(0).path("ruleName").asText()).isEqualTo("全场上浮");

        checkOutAt(session, id, DAY.withHour(12));
        json(post("/api/bookings/" + id + "/settle").session(session));
        Bill bill = billMapper.findByBookingId(id);
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("180.00");
        assertThat(bill.getDetailJson()).contains("全场上浮");

        // 规则删掉之后，已结算的账单照旧读得出"当时命中了哪条"
        rateRuleService.delete(rateRuleService.list(room.getPropertyId()).get(0).id());
        JsonNode view = json(get("/api/bookings/" + id + "/bill").session(session));
        assertThat(view.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(view.path("data").path("lines").get(0).path("ruleName").asText()).isEqualTo("全场上浮");
    }

    @Test
    @DisplayName("停用的规则不参与计价")
    void disabledRuleIsIgnored() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);

        rateRuleService.save(new RateRuleReq(null, room.getPropertyId(), null, "未启用的加价",
                null, null, List.of(), null, null, "MULTIPLY", new BigDecimal("2.00"), 100, false));

        JsonNode preview = json(post("/api/bookings/" + id + "/bill/preview").session(session)).path("data");
        assertThat(preview.path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");
        // 返回体配了 non_null 包含策略，没命中规则时 ruleName 这个字段干脆不存在
        assertThat(preview.path("lines").get(0).path("ruleName").asText("")).isEmpty();
    }

    @Test
    @DisplayName("计费粒度是配置项：同一张单，粒度 30 比粒度 60 收得少")
    void chargeUnitIsConfigurable() throws Exception {
        MockHttpSession session = login();
        // 起步 3 小时；9:00–12:20 的超时正好是 20 分钟，最能见出粒度差异
        Room room = room("100.00", 3, "20.00", 60);
        long id = booking(session, room, 9, 12, 20);

        // 粒度 60：不足一小时按一小时 → 100 + 20
        assertThat(json(post("/api/bookings/" + id + "/bill/preview").session(session))
                .path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");

        Long typeId = room.getRoomTypeId();
        var type = roomTypeService.require(typeId);
        roomTypeService.save(new RoomTypeReq(typeId, type.getPropertyId(), type.getName(),
                type.getBasePrice(), type.getMinHours(), type.getExtraHourPrice(), 30,
                type.getCleanBeforeMinutes(), type.getCleanAfterMinutes(),
                type.getFullDayStart(), type.getFullDayEnd()));

        // 粒度 30：20 分钟按半小时收 → 100 + 10
        JsonNode fine = json(post("/api/bookings/" + id + "/bill/preview").session(session)).path("data");
        assertThat(fine.path("chargeUnitMinutes").asInt()).isEqualTo(30);
        assertThat(fine.path("totalAmount").decimalValue()).isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("超时未退房要补收，提前退房不退差价")
    void lateStayIsChargedEarlyStayIsNotRefunded() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long late = booking(session, room, 9, 14);
        long early = booking(session, room, 15, 18);

        // 预订区间本身值 100 + 3×20 = 160
        assertThat(json(post("/api/bookings/" + late + "/bill/preview").session(session))
                .path("data").path("totalAmount").decimalValue()).isEqualByComparingTo("160.00");

        // 超时：退房时刻取 now，必然晚于昨天 14:00，所以结算金额只会比 160 更贵。
        // 不断具体数字，因为"晚了多久"取决于测试跑在几点 —— 那不该是一条断言
        checkInAndOut(session, late);
        JsonNode lateBill = json(post("/api/bookings/" + late + "/settle").session(session)).path("data");
        assertThat(lateBill.path("notice").asText()).contains("晚于计划");
        assertThat(lateBill.path("calcEnd").asText())
                .startsWith(LocalDateTime.now().toLocalDate().toString());
        assertThat(lateBill.path("totalAmount").decimalValue()).isGreaterThan(new BigDecimal("160.00"));

        // 提前退房：把实际退房时刻钉成计划中点。用 now() 会让断言取决于跑在几点，
        // 而"早退不退"这条口径本身和几点无关
        checkOutAt(session, early, DAY.withHour(16).withMinute(30));
        JsonNode earlyBill = json(post("/api/bookings/" + early + "/settle").session(session)).path("data");
        assertThat(earlyBill.path("notice").asText()).contains("不退差价");
        // 15:00–18:00 按原单收：起步 100 + 超时 1 小时 20 = 120，而不是按到 16:30 收
        assertThat(earlyBill.path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(earlyBill.path("calcEnd").asText()).contains("18:00");
    }

    @Test
    @DisplayName("只有 CHECKED_OUT 能结算，且不能重复结算")
    void settleGuardsTheStateMachine() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);

        // 还没入住就想收钱
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session))
                .path("code").asInt()).isEqualTo(3002);
        assertThat(billMapper.findByBookingId(id)).isNull();
        assertThat(bookingMapper.selectById(id).getStatus()).isEqualTo(Booking.CONFIRMED);

        // 在住期间也不行 —— 退房才知道到底住了多久
        mockMvc.perform(post("/api/bookings/" + id + "/check-in").session(session))
                .andExpect(status().isOk());
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session))
                .path("code").asInt()).isEqualTo(3002);

        mockMvc.perform(post("/api/bookings/" + id + "/check-out").session(session))
                .andExpect(status().isOk());
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session))
                .path("code").asInt()).isZero();

        // 第二次结算必须被拒，而且不许留下第二张账单
        int code = json(post("/api/bookings/" + id + "/settle").session(session)).path("code").asInt();
        assertThat(code).isEqualTo(3002);
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session)).path("message").asText())
                .contains("已结算");
        Integer rows = jdbc.queryForObject("select count(*) from bill where booking_id = ?",
                Integer.class, id);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("取消的单没什么可算")
    void cancelledBookingHasNothingToBill() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);
        mockMvc.perform(post("/api/bookings/" + id + "/cancel").session(session)).andExpect(status().isOk());

        assertThat(json(post("/api/bookings/" + id + "/bill/preview").session(session))
                .path("code").asInt()).isEqualTo(3007);
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session))
                .path("code").asInt()).isEqualTo(3002);
    }

    @Test
    @DisplayName("全日房按模板时长算起步价，只有加时才收超时费")
    void fullDayCoversTheTemplateAndChargesOnlyTheExtension() throws Exception {
        MockHttpSession session = login();
        // 房型模板 14:00 → 次日 12:00，起步 388 覆盖这 22 小时
        Room room = room("388.00", 3, "40.00", 60);
        long id = booking(session, room, DAY.withHour(14), DAY.plusDays(1).withHour(12), "FULL_DAY");

        JsonNode q = json(post("/api/bookings/" + id + "/bill/preview").session(session)).path("data");
        assertThat(q.path("coveredMinutes").asInt()).isEqualTo(22 * 60);
        assertThat(q.path("totalAmount").decimalValue()).isEqualByComparingTo("388.00");
        assertThat(q.path("lines")).hasSize(1);

        // 退房延到次日 14:00 = 模板之后再 2 小时 → 388 + 2×40。
        // 走 JDBC 而不是 /extend 接口：那条路要重跑冲突检测、要 IN_USE 状态，
        // 而这里要验的只是"全日房的起步覆盖时长是模板时长"这一件事
        jdbc.update("update booking set end_time = ? where id = ?",
                DAY.plusDays(1).withHour(14), id);
        JsonNode extended = json(post("/api/bookings/" + id + "/bill/preview").session(session)).path("data");
        assertThat(extended.path("totalAmount").decimalValue()).isEqualByComparingTo("468.00");
        assertThat(extended.path("lines")).hasSize(2);
        assertThat(extended.path("lines").get(1).path("chargeMinutes").asInt()).isEqualTo(120);
    }

    // ------------------------------------------------------------------ 夹具

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    /** 一间房 + 一个可配的房型。价格、起步时长、超时单价、计费粒度都由调用方给。 */
    private Room room(String basePrice, int minHours, String extraHourPrice, int chargeUnitMinutes) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        PropertyVO p = propertyService.list().stream().filter(x -> x.name().equals("店" + tag))
                .findFirst().orElseThrow();
        roomTypeService.save(new RoomTypeReq(null, p.id(), "型" + tag, new BigDecimal(basePrice),
                minHours, new BigDecimal(extraHourPrice), chargeUnitMinutes, 0, 0,
                LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(p.id()).get(0).id();
        return roomService.save(new RoomReq(null, p.id(), tid, "R" + tag, 1, Room.ON_SALE));
    }

    private long booking(MockHttpSession session, Room room, int fromHour, int toHour) throws Exception {
        return booking(session, room, DAY.withHour(fromHour), DAY.withHour(toHour), "HOURLY");
    }

    private long booking(MockHttpSession session, Room room, int fromHour, int toHour, int toMinute)
            throws Exception {
        return booking(session, room, DAY.withHour(fromHour),
                DAY.withHour(toHour).withMinute(toMinute), "HOURLY");
    }

    private long booking(MockHttpSession session, Room room,
                         LocalDateTime start, LocalDateTime end, String saleType) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":" + room.getId() + ",\"guestName\":\"计费客人\","
                                + "\"startTime\":\"" + start + "\","
                                + "\"endTime\":\"" + end + "\","
                                + "\"saleType\":\"" + saleType + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return MAPPER.readTree(body).path("data").path("id").asLong();
    }

    private void checkInAndOut(MockHttpSession session, long bookingId) throws Exception {
        mockMvc.perform(post("/api/bookings/" + bookingId + "/check-in").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/bookings/" + bookingId + "/check-out").session(session))
                .andExpect(status().isOk());
    }

    /**
     * 退房并把实际退房时刻钉成一个固定值。
     *
     * <p>为什么需要：退房走的是真实流转，{@code actual_check_out} 会被写成 now()，
     * 而基准日是<b>昨天</b>（入住时刻必须落在可售窗口里，又不能是将来）。于是
     * "晚走要补"会补出一整夜的费用，凡是断言死金额的用例都会被打飞。
     * 超时补收那条用例单独用不钉时刻的 {@link #checkInAndOut}，正好把这个分支测住。
     */
    private void checkOutAt(MockHttpSession session, long bookingId, LocalDateTime at) throws Exception {
        checkInAndOut(session, bookingId);
        jdbc.update("update booking set actual_check_out = ? where id = ?", at, bookingId);
    }

    /**
     * 业务错误藏在 HTTP 200 的 {@code code} 里（本项目全局返回体的约定），
     * 所以断言一律先取 JSON 再判 code，而不是判 HTTP 状态。
     */
    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        String body = mockMvc.perform(rb).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return MAPPER.readTree(body);
    }
}
