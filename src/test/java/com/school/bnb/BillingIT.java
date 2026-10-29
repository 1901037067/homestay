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
import com.school.bnb.booking.BookingKernel;
import com.school.bnb.entity.Bill;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.mapper.BillMapper;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.OperationLogService;
import com.school.bnb.service.RateRuleService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.PropertyVO;
import com.school.bnb.vo.RateRuleVO;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    @Autowired BookingKernel kernel;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired RateRuleService rateRuleService;
    @Autowired OperationLogService opLogService;

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
                null, null, List.of(), null, null, "MULTIPLY", new BigDecimal("1.50"), "ALL", 100, true));

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
                null, null, List.of(), null, null, "MULTIPLY", new BigDecimal("2.00"), "ALL", 100, false));

        JsonNode preview = json(post("/api/bookings/" + id + "/bill/preview").session(session)).path("data");
        assertThat(preview.path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");
        // 返回体配了 non_null 包含策略，没命中规则时 ruleName 这个字段干脆不存在
        assertThat(preview.path("lines").get(0).path("ruleName").asText("")).isEmpty();
    }

    @Test
    @DisplayName("计费粒度是配置项，但只对之后新开的单生效（旧单已快照）")
    void chargeUnitIsConfigurableButFrozenPerBooking() throws Exception {
        MockHttpSession session = login();
        // 起步 3 小时；9:00–12:20 的超时是 20 分钟，最能见出粒度差异
        Room coarse = room("100.00", 3, "20.00", 60);
        long oldId = booking(session, coarse, 9, 12, 20);
        assertThat(total(session, oldId)).as("粒度 60：不足一小时按一小时 → 100 + 20")
                .isEqualByComparingTo("120.00");

        // 把粒度改成 30，再新开一间同房型的房
        Long typeId = coarse.getRoomTypeId();
        var type = roomTypeService.require(typeId);
        roomTypeService.save(new RoomTypeReq(typeId, type.getPropertyId(), type.getName(),
                type.getBasePrice(), type.getMinHours(), type.getExtraHourPrice(), 30,
                type.getCleanBeforeMinutes(), type.getCleanAfterMinutes(),
                type.getFullDayStart(), type.getFullDayEnd()));
        Room fine = roomService.save(new RoomReq(null, type.getPropertyId(), typeId,
                "R-" + System.nanoTime(), 1, Room.ON_SALE));
        long newId = booking(session, fine, 9, 12, 20);

        JsonNode fineBill = json(post("/api/bookings/" + newId + "/bill/preview").session(session)).path("data");
        assertThat(fineBill.path("chargeUnitMinutes").asInt()).isEqualTo(30);
        assertThat(fineBill.path("totalAmount").decimalValue())
                .as("粒度 30：20 分钟按半小时收 → 100 + 10").isEqualByComparingTo("110.00");
        // 旧单不受影响：它在下单时就把粒度快照成 60 了
        assertThat(total(session, oldId)).isEqualByComparingTo("120.00");
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

        // 在住期间也不行 —— 退房才知道到底住了多久（时刻显式给，理由见 checkInAndOut）
        kernel.checkIn(id, DAY.withHour(9));
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session))
                .path("code").asInt()).isEqualTo(3002);

        kernel.checkOut(id, DAY.withHour(12), 0);
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

    @Test
    @DisplayName("清空规则上的限制必须真的落库 —— 默认更新策略会跳过 null 字段")
    void clearingRuleConstraintsTakesEffect() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);
        long id = booking(session, room, 9, 12);      // 周一 9:00–12:00 → 平时 100 + 20

        // 一条被"日期 + 星期 + 房型"三重限制框住的规则，此刻一块都不该命中
        rateRuleService.save(new RateRuleReq(null, room.getPropertyId(), room.getRoomTypeId(),
                "元旦周末特价", LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 2), List.of(6, 7),
                null, null, "OVERRIDE", new BigDecimal("50.00"), "ALL", 100, true));
        assertThat(total(session, id)).isEqualByComparingTo("120.00");

        // 把三块限制全部清空 = 改成一条民宿通用、不限日期星期的规则
        Long ruleId = rateRuleService.list(room.getPropertyId()).get(0).id();
        rateRuleService.save(new RateRuleReq(ruleId, room.getPropertyId(), null, "民宿通用特价",
                null, null, List.of(), null, null, "OVERRIDE", new BigDecimal("50.00"), "ALL", 100, true));

        RateRuleVO after = rateRuleService.list(room.getPropertyId()).get(0);
        assertThat(after.roomTypeId()).as("房型作用域要真的清掉").isNull();
        assertThat(after.validFrom()).as("日期限制要真的清掉").isNull();
        assertThat(after.weekdays()).as("星期限制要真的清掉").isEmpty();
        // 页面提示"已保存"而库里没变，是这个 bug 最难发现的地方 —— 所以要用钱来验，不是用提示来验
        assertThat(total(session, id)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("下单后改房型配置，未结算旧单金额不变（计费基准在下单时已快照）")
    void pricingFramesAreFrozenAtBookingTime() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);       // 起步 2 小时
        long id = booking(session, room, 9, 12);          // 3 小时 → 100 + 1h×20 = 120
        assertThat(total(session, id)).isEqualByComparingTo("120.00");

        // 把起步时长改成 3 小时。若结算时才读房型，超时段就没了，只剩 100 —— 旧单金额被悄悄改小
        Long typeId = room.getRoomTypeId();
        var type = roomTypeService.require(typeId);
        roomTypeService.save(new RoomTypeReq(typeId, type.getPropertyId(), type.getName(),
                type.getBasePrice(), 3, type.getExtraHourPrice(), type.getChargeUnitMinutes(),
                type.getCleanBeforeMinutes(), type.getCleanAfterMinutes(),
                type.getFullDayStart(), type.getFullDayEnd()));

        assertThat(total(session, id)).as("试算按快照的 2 小时起步").isEqualByComparingTo("120.00");
        checkOutAt(session, id, DAY.withHour(12));
        assertThat(json(post("/api/bookings/" + id + "/settle").session(session))
                .path("data").path("totalAmount").decimalValue())
                .as("结算也按快照").isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("幂等回放不覆盖计费快照，也不重复记开单日志")
    void replayDoesNotRewriteThePricingSnapshot() throws Exception {
        MockHttpSession session = login();
        Room room = room("100.00", 2, "20.00", 60);          // 起步 2 小时
        String rid = "REQ-IDEM-" + System.nanoTime();
        long id = bookingWithRequestId(session, room, 9, 12, rid);
        assertThat(total(session, id)).isEqualByComparingTo("120.00");

        // 改起步时长：若回放时重写快照，这张单会从 120 变成 100
        Long typeId = room.getRoomTypeId();
        var type = roomTypeService.require(typeId);
        roomTypeService.save(new RoomTypeReq(typeId, type.getPropertyId(), type.getName(),
                type.getBasePrice(), 3, type.getExtraHourPrice(), type.getChargeUnitMinutes(),
                type.getCleanBeforeMinutes(), type.getCleanAfterMinutes(),
                type.getFullDayStart(), type.getFullDayEnd()));

        // 第一次提交成功但响应超时，前台用同一个 requestId 重试
        assertThat(bookingWithRequestId(session, room, 9, 12, rid))
                .as("回放返回原单").isEqualTo(id);
        assertThat(total(session, id)).as("快照不能被今天的配置覆盖").isEqualByComparingTo("120.00");
        assertThat(opLogService.page(1, 10, "BOOKING_CREATE", "booking", id, null).getTotal())
                .as("回放不该再记一条开单日志").isEqualTo(1);
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

    private long bookingWithRequestId(MockHttpSession session, Room room, int fromHour, int toHour,
                                      String requestId) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":" + room.getId() + ",\"guestName\":\"计费客人\","
                                + "\"startTime\":\"" + DAY.withHour(fromHour) + "\","
                                + "\"endTime\":\"" + DAY.withHour(toHour) + "\","
                                + "\"saleType\":\"HOURLY\",\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return MAPPER.readTree(body).path("data").path("id").asLong();
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

    /**
     * 入住/退房走内核并显式给时刻，而不是走 HTTP 接口。
     *
     * <p>两个原因：① HTTP 的 check-in/check-out 用 {@code now()}，而这里的订单一律是<b>昨天</b>的，
     * 会撞上"到店时刻已过预订结束时刻"的新规则（该规则是对的，见 OccupancyTruthIT ⑩）；
     * ② 计费要断言死金额，就必须钉住实际退房时刻，不能让 now() 决定。
     * 结算仍然走 HTTP —— 那才是要验证的入口（含操作人）。
     */
    private void checkInAndOut(MockHttpSession session, long bookingId) {
        kernel.checkIn(bookingId, DAY.withHour(9));
        kernel.checkOut(bookingId, LocalDateTime.now(), 0);
    }

    /**
     * 退房并把实际退房时刻钉成一个固定值。
     *
     * <p>为什么需要：退房走的是真实流转，{@code actual_check_out} 会被写成 now()，
     * 而基准日是<b>昨天</b>（入住时刻必须落在可售窗口里，又不能是将来）。于是
     * "晚走要补"会补出一整夜的费用，凡是断言死金额的用例都会被打飞。
     * 超时补收那条用例单独用不钉时刻的 {@link #checkInAndOut}，正好把这个分支测住。
     */
    private void checkOutAt(MockHttpSession session, long bookingId, LocalDateTime at) {
        kernel.checkIn(bookingId, DAY.withHour(9));
        kernel.checkOut(bookingId, at, 0);
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

    /** 试算合计。规则改没改成功，最终要用钱来验，而不是用"保存成功"的提示来验。 */
    private BigDecimal total(MockHttpSession session, long bookingId) throws Exception {
        return json(post("/api/bookings/" + bookingId + "/bill/preview").session(session))
                .path("data").path("totalAmount").decimalValue();
    }
}
