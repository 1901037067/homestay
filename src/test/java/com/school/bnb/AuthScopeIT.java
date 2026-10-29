package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.bnb.booking.BookingKernel;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.dto.RateRuleReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.dto.UserReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.service.RateRuleService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 按民宿隔离（账号管理 + 数据可见范围）。
 *
 * <p>种子账号里 {@code staff}（前台小张）绑在 1 号民宿，{@code admin} 不绑 = 店长。
 * 这一类测的是同一件事的两个方向：<b>越界必须被拒</b>，而<b>本分内的操作不能被一起挡掉</b>。
 * 只测前一半，很容易写出一个"什么都 403"的实现还全绿。
 *
 * <p>覆盖形状是"本店/别店 × 列表/详情/新增/修改/删除/账单/导出"。第十轮审阅抓到的正是
 * 这张表里缺的两列 —— <b>按 id 的详情</b>与<b>路径 id 和请求体归属不一致的修改</b>：
 * 列表过滤做对了，照样能用一个 id 直接读到或改掉别店的记录。
 */
@AutoConfigureMockMvc
class AuthScopeIT extends AbstractMysqlIT {

    /** V2 结构性种子里就有的两家店：云栖民宿(1) 与 山语民宿(2)。 */
    private static final long P1 = 1L;
    private static final long P2 = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingKernel kernel;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired RateRuleService rateRuleService;
    @Autowired UserService userService;

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    /** 在指定民宿里造一间可售房间，返回房间 id。没有登录上下文 = 内部调用，不受可见范围约束。 */
    private long roomIn(long propertyId) {
        String tag = String.valueOf(System.nanoTime());
        long typeId = roomTypeService.save(new RoomTypeReq(null, propertyId, "隔离用房型" + tag,
                new BigDecimal("100.00"), 2, new BigDecimal("20.00"), null, 0, 0,
                LocalTime.of(14, 0), LocalTime.of(12, 0))).getId();
        return roomService.save(new RoomReq(null, propertyId, typeId, "R" + tag, 1, Room.ON_SALE)).getId();
    }

    private JsonNode data(MockHttpSession session, String url) throws Exception {
        MvcResult r = mockMvc.perform(get(url).session(session))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        assertThat(body.path("code").asInt())
                .as("GET %s 应当成功，实际返回 %s", url, body.path("message").asText()).isZero();
        return body.path("data");
    }

    private int codeOf(MockHttpSession session, org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult r = mockMvc.perform(builder.session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("code").asInt();
    }

    private int codeOf(MockHttpSession session, String url) throws Exception {
        return codeOf(session, get(url));
    }

    private int postJson(MockHttpSession session, String url, Object body) throws Exception {
        return codeOf(session, post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("店长看全部门店，前台只看自己那一家")
    void propertyListIsScoped() throws Exception {
        MockHttpSession manager = login("admin");
        MockHttpSession staff = login("staff");

        assertThat(data(manager, "/api/properties")).hasSizeGreaterThan(1);

        JsonNode mine = data(staff, "/api/properties");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("id").asLong()).isEqualTo(P1);
    }

    @Test
    @DisplayName("前台请求别家店的房间列表：直接 1003，而不是悄悄给一个空列表")
    void explicitOutOfScopeRequestIsRejected() throws Exception {
        MockHttpSession staff = login("staff");
        roomIn(P1);
        roomIn(P2);

        assertThat(codeOf(staff, "/api/rooms?page=1&size=20&propertyId=" + P2))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(staff, "/api/calendar?propertyId=" + P2))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(staff, "/api/stats/report?propertyId=" + P2))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(staff, "/api/rooms/availability?propertyId=" + P2
                + "&start=" + at(10) + "&end=" + at(12) + "&saleType=HOURLY"))
                .as("可售查询与下单同一口径，否则前端点了才被拒")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("前台不带民宿参数时自动收窄到自己那家，列表里不会出现别家店的房间")
    void implicitNarrowingCoversEveryRow() throws Exception {
        roomIn(P1);
        long foreign = roomIn(P2);
        MockHttpSession staff = login("staff");

        JsonNode rows = data(staff, "/api/rooms?page=1&size=100").path("records");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.path("propertyId").asLong())
                .as("前台的房间列表里混进了别家店的数据").isEqualTo(P1));
        // 对照：那间别家店的房间确实存在，店长看得见 —— 否则上面的"收窄"可能只是因为没数据
        assertThat(data(login("admin"), "/api/rooms?page=1&size=500&propertyId=" + P2).path("records"))
                .anySatisfy(r -> assertThat(r.path("id").asLong()).isEqualTo(foreign));
    }

    @Test
    @DisplayName("给别家店的房间开单必须被拒；给自己店的房间开单照常成功")
    void bookingRespectsScopeBothWays() throws Exception {
        long mine = roomIn(P1);
        long theirs = roomIn(P2);
        MockHttpSession staff = login("staff");

        assertThat(postJson(staff, "/api/bookings", booking(theirs, "越界客人")))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(postJson(staff, "/api/bookings", booking(mine, "本店客人")))
                .as("隔离不能把合法路径一起挡掉").isZero();
    }

    @Test
    @DisplayName("操作日志按民宿隔离：摘要里有房号和客人姓名，那就是民宿数据")
    void auditLogIsScoped() throws Exception {
        long mine = roomIn(P1);
        long theirs = roomIn(P2);
        MockHttpSession manager = login("admin");
        MockHttpSession staff = login("staff");

        // 店长在两家店各留一条动作，再让前台看自己的审计页
        roomService.changeStatus(mine, Room.MAINTENANCE);
        roomService.changeStatus(theirs, Room.MAINTENANCE);

        JsonNode rows = data(staff, "/api/operation-logs?page=1&size=100").path("records");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.path("propertyId").asLong())
                .as("前台看到了别家店的审计行：" + r.path("summary").asText()).isEqualTo(P1));

        // 店长那份是全量，含跨店动作 —— 隔离只收窄前台，不是把审计页废掉
        JsonNode all = data(manager, "/api/operation-logs?page=1&size=200").path("records");
        assertThat(all).anySatisfy(r -> assertThat(r.path("propertyId").asLong()).isEqualTo(P2));
    }

    @Test
    @DisplayName("账号页只对店长开放：前台读和写都是 1003")
    void userAdminIsManagerOnly() throws Exception {
        MockHttpSession staff = login("staff");
        assertThat(codeOf(staff, "/api/users")).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(postJson(staff, "/api/users",
                new UserReq(null, "sneak" + System.nanoTime(), "偷建的前台", "Admin@123", P1, true)))
                .as("不能因为前端藏了入口就以为写接口也安全")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("停用是立刻生效的：那个人的下一次请求就 401，不用等他过期")
    void disablingAnAccountKicksTheLiveSession() throws Exception {
        String username = "temp" + System.nanoTime();
        var created = userService.save(new UserReq(null, username, "临时前台", "Admin@123", P1, true));
        MockHttpSession session = login(username);
        assertThat(data(session, "/api/properties")).hasSize(1);

        userService.save(new UserReq(created.id(), username, "临时前台", null, P1, false));

        mockMvc.perform(get("/api/properties").session(session))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------ 第十轮审阅：按 id 的详情与修改路径

    @Test
    @DisplayName("按 id 读详情也越不了界：房间、订单、已结算账单快照三条路径都要过归属校验")
    void detailEndpointsRejectForeignIds() throws Exception {
        long[] mine = fixtureIn(P1);
        long[] theirs = fixtureIn(P2);
        MockHttpSession manager = login("admin");
        MockHttpSession staff = login("staff");

        // 先在别店造一张订单并走完入住→退房→结算，前台才谈得上"拿别人的单号来读"。
        // 已结算那条分支直接读 bill 快照、不经过 preview，是最容易漏校验的一条路
        long foreignBooking = settledBookingIn(theirs[1]);
        long myBooking = settledBookingIn(mine[1]);
        // 结算要留在店长身份下做：settledBookingIn 只推进到 CHECKED_OUT，
        // 而"已结算读快照"这条分支才是审阅指出的漏点
        assertThat(postEmpty(manager, "/api/bookings/" + foreignBooking + "/settle")).isZero();
        assertThat(postEmpty(manager, "/api/bookings/" + myBooking + "/settle")).isZero();

        assertThat(codeOf(staff, "/api/rooms/" + theirs[1]))
                .as("别店房间详情").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(staff, "/api/bookings/" + foreignBooking))
                .as("别店订单详情：姓名、电话、金额都在里面").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(staff, "/api/bookings/" + foreignBooking + "/bill"))
                .as("别店已结算账单快照").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(codeOf(staff, "/api/bookings/" + myBooking + "/bill"))
                .as("本店账单照常可读，隔离不能把合法路径一起挡掉").isZero();
    }

    @Test
    @DisplayName("PUT 路径给别店 id、请求体给本店归属：原归属也要校验，改不动")
    void updateCannotReachOtherStoreByMismatchedBody() throws Exception {
        MockHttpSession staff = login("staff");
        long[] mine = fixtureIn(P1);
        long[] theirs = fixtureIn(P2);
        long foreignRule = rateRuleIn(P2);

        // 三条都只认"请求体里的新归属可见"，就会把别店的记录搬到自己店名下。
        // 请求体刻意做成"本店房型 + 本店归属"这样自洽的形式 —— 否则会被"房型不属于这家民宿"
        // 之类的普通校验先挡掉，测不到真正要测的那一句原归属校验
        assertThat(putJson(staff, "/api/rooms/" + theirs[1],
                new RoomReq(theirs[1], P1, mine[0], "搬过来", 1, Room.ON_SALE)))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(putJson(staff, "/api/room-types/" + theirs[0],
                new RoomTypeReq(theirs[0], P1, "搬过来的房型", new BigDecimal("100.00"),
                        2, new BigDecimal("20.00"), null, 0, 0,
                        LocalTime.of(14, 0), LocalTime.of(12, 0))))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(putJson(staff, "/api/rate-rules/" + foreignRule,
                rateRuleBody(foreignRule, P1)))
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 库里确实没被动过 —— 只看接口返回码会漏掉"报错但已经改了"这种半成功
        assertThat(jdbc.queryForObject("select property_id from room where id = ?", Long.class, theirs[1]))
                .isEqualTo(P2);
        assertThat(jdbc.queryForObject("select property_id from rate_rule where id = ?", Long.class, foreignRule))
                .isEqualTo(P2);
    }

    @Test
    @DisplayName("删除与导出也不能指向别店")
    void deleteAndExportRespectScope() throws Exception {
        MockHttpSession staff = login("staff");
        long[] theirs = fixtureIn(P2);

        assertThat(codeOf(staff, "DELETE", "/api/rooms/" + theirs[1]))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(1) from room where id = ?", Integer.class, theirs[1]))
                .as("被拒的删除不能真的把别店的房删掉").isEqualTo(1);
        assertThat(codeOf(staff, "/api/stats/export?propertyId=" + P2))
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------------------- 夹具

    /** 在指定民宿造一对"房型 + 房间"，返回 {房型 id, 房间 id}。 */
    private long[] fixtureIn(long propertyId) {
        String tag = String.valueOf(System.nanoTime());
        long typeId = roomTypeService.save(new RoomTypeReq(null, propertyId, "隔离用房型" + tag,
                new BigDecimal("100.00"), 2, new BigDecimal("20.00"), null, 0, 0,
                LocalTime.of(14, 0), LocalTime.of(12, 0))).getId();
        long roomId = roomService.save(new RoomReq(null, propertyId, typeId, "R" + tag, 1, Room.ON_SALE)).getId();
        return new long[]{typeId, roomId};
    }

    private long rateRuleIn(long propertyId) {
        var saved = rateRuleService.save(new RateRuleReq(null, propertyId, null, "隔离用规则" + System.nanoTime(),
                null, null, null, null, null, "MULTIPLY", new BigDecimal("1.2"), "ALL", 100, true));
        return saved.getId();
    }

    private RateRuleReq rateRuleBody(long id, long propertyId) {
        return new RateRuleReq(id, propertyId, null, "改写过的规则", null, null, null, null, null,
                "MULTIPLY", new BigDecimal("1.5"), "ALL", 100, true);
    }

    /** 用内核直接推进到 SETTLED：HTTP 的入住/退房取 now()，会把测试变成一天里某些时段才跑得过。 */
    private long settledBookingIn(long roomId) {
        LocalDateTime start = LocalDateTime.now().minusHours(5);
        LocalDateTime end = start.plusHours(2);
        long id = kernel.createBooking(new CreateBookingCmd(roomId, "客人" + System.nanoTime(), null,
                start, end, 0, 0, Booking.CONFIRMED, null, null, "HOURLY")).getId();
        kernel.checkIn(id, start);
        kernel.checkOut(id, end, 0);
        return id;
    }

    private int putJson(MockHttpSession session, String url, Object body) throws Exception {
        return codeOf(session, put(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    /** 无请求体的 POST（结算这种" just do it"动作）。 */
    private int postEmpty(MockHttpSession session, String url) throws Exception {
        return codeOf(session, post(url));
    }

    private int codeOf(MockHttpSession session, String method, String url) throws Exception {
        return codeOf(session, "DELETE".equals(method) ? delete(url) : get(url));
    }

    private static LocalDateTime at(int hour) {
        return LocalDate.now().plusDays(1).atTime(hour, 0);
    }

    private java.util.Map<String, Object> booking(long roomId, String guest) {
        return java.util.Map.of(
                "roomId", roomId,
                "guestName", guest,
                "startTime", at(10).toString(),
                "endTime", at(12).toString(),
                "saleType", "HOURLY",
                "status", "CONFIRMED");
    }
}
