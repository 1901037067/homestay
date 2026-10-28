package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.booking.BookingKernel;
import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.service.BookingService;
import com.school.bnb.service.CalendarService;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.CalendarBlock;
import com.school.bnb.vo.CalendarRoomVO;
import com.school.bnb.vo.CalendarVO;
import com.school.bnb.vo.PropertyVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 房态日历数据源（W4）。重点验证营业日切割与跨零点占用。 */
class CalendarIT extends AbstractMysqlIT {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    @Autowired CalendarService calendarService;
    @Autowired BookingKernel kernel;
    @Autowired BookingService bookingService;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    /**
     * 开一家店。open/close 给全天，是为了能订到凌晨时段 ——
     * 否则可售窗口校验会先把"凌晨 2 点入住"拒掉，就测不到切割点旋转了。
     */
    private PropertyVO property(LocalTime dayCut) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, dayCut,
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        return propertyService.list().stream()
                .filter(p -> p.name().equals("店" + tag)).findFirst().orElseThrow();
    }

    private Room room(Long propertyId, int cleanAfter) {
        String tag = String.valueOf(System.nanoTime());
        roomTypeService.save(new RoomTypeReq(null, propertyId, "型" + tag, new BigDecimal("100.00"),
                1, new BigDecimal("20.00"), null, 0, cleanAfter, LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long typeId = roomTypeService.list(propertyId).get(0).id();
        return roomService.save(new RoomReq(null, propertyId, typeId, "R" + tag, 1, Room.ON_SALE));
    }

    private void book(Long roomId, LocalDateTime start, LocalDateTime end) {
        bookingService.create(new CreateBookingReq(roomId, "客人" + System.nanoTime(), null,
                start, end, Booking.CONFIRMED, null));
    }

    /** 跨天的整日占用必须显式声明 FULL_DAY —— 钟点跨天会被可售规则拒掉。 */
    private void bookFullDay(Long roomId, LocalDateTime start, LocalDateTime end) {
        bookingService.create(new CreateBookingReq(roomId, "客人" + System.nanoTime(), null,
                start, end, Booking.CONFIRMED, null, "FULL_DAY", null));
    }

    private CalendarRoomVO row(CalendarVO vo, Long roomId) {
        return vo.rooms().stream().filter(r -> r.roomId().equals(roomId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("窗口按营业日切割点旋转，不是自然日")
    void windowRotatesByDayCut() {
        PropertyVO p = property(LocalTime.of(6, 0));
        room(p.id(), 0);

        CalendarVO vo = calendarService.calendar(p.id(), DAY);
        assertThat(vo.dayCutTime()).isEqualTo(LocalTime.of(6, 0));
        assertThat(vo.windowStart()).isEqualTo(DAY.atTime(6, 0));
        assertThat(vo.windowEnd()).isEqualTo(DAY.plusDays(1).atTime(6, 0));
    }

    @Test
    @DisplayName("凌晨 2 点的钟点单归在前一个营业日，而不是它自己的自然日")
    void earlyMorningBelongsToPreviousBusinessDay() {
        PropertyVO p = property(LocalTime.of(6, 0));
        Room r = room(p.id(), 0);
        book(r.getId(), DAY.plusDays(1).atTime(2, 0), DAY.plusDays(1).atTime(5, 0));

        assertThat(row(calendarService.calendar(p.id(), DAY), r.getId()).blocks())
                .as("DAY 的营业日窗口是 [06:00, 次日06:00)，凌晨 2 点在里面")
                .hasSize(1);
        assertThat(row(calendarService.calendar(p.id(), DAY.plusDays(1)), r.getId()).blocks())
                .as("次日窗口从 06:00 开始，凌晨 2 点不该重复出现")
                .isEmpty();
    }

    @Test
    @DisplayName("跨零点全日房在同一个营业日窗口里是完整一块，不被自然日切开")
    void crossMidnightStaysOneBlock() {
        PropertyVO p = property(LocalTime.of(6, 0));
        Room r = room(p.id(), 60);
        bookFullDay(r.getId(), DAY.atTime(14, 0), DAY.plusDays(1).atTime(12, 0));

        List<CalendarBlock> blocks = row(calendarService.calendar(p.id(), DAY), r.getId()).blocks();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getStartTime()).isEqualTo(DAY.atTime(14, 0));
        assertThat(blocks.get(0).getEndTime()).isEqualTo(DAY.plusDays(1).atTime(12, 0));
        // 缓冲 60 分钟要体现在 block 上，日历画的就是"被挡住的时间"
        assertThat(blocks.get(0).getBlockEnd()).isEqualTo(DAY.plusDays(1).atTime(13, 0));
    }

    @Test
    @DisplayName("取消后块消失；退房后转成清扫块，点完成才真正消失")
    void blocksDisappearAfterRelease() {
        PropertyVO p = property(LocalTime.of(6, 0));
        Room r = room(p.id(), 30);   // 带退房缓冲，退房才会留下清扫块
        var b = bookingService.create(new CreateBookingReq(r.getId(), "张三", null,
                DAY.atTime(14, 0), DAY.atTime(18, 0), Booking.CONFIRMED, null));
        assertThat(row(calendarService.calendar(p.id(), DAY), r.getId()).blocks()).hasSize(1);

        bookingService.cancel(b.id());
        assertThat(row(calendarService.calendar(p.id(), DAY), r.getId()).blocks()).isEmpty();

        var b2 = bookingService.create(new CreateBookingReq(r.getId(), "李四", null,
                DAY.atTime(20, 0), DAY.atTime(22, 0), Booking.CONFIRMED, null));
        bookingService.checkIn(b2.id());
        bookingService.checkOut(b2.id());

        // 清扫块按<b>实际退房时刻</b>落库，而 checkOut 用的是 now() —— 本用例的订单排在 12 月，
        // 所以它落在日历窗口之外。这里直接查表断言语义，不借日历窗口（那样会依赖运行时刻）。
        assertThat(jdbc.queryForObject(
                "select kind from occupancy where booking_id = ?", String.class, b2.id()))
                .as("退房留下的是清扫占用，不是房时间占用")
                .isEqualTo("CLEANING");

        bookingService.finishCleaning(r.getId());
        assertThat(jdbc.queryForObject("select count(1) from occupancy where room_id = ?",
                Integer.class, r.getId())).isZero();
    }

    @Test
    @DisplayName("日历只返回该店的房间，维修与下架的也要出现（前端灰显而不是消失）")
    void roomsScopedByPropertyAndIncludeNonSaleable() {
        PropertyVO a = property(LocalTime.of(6, 0));
        PropertyVO b = property(LocalTime.of(6, 0));
        Room r1 = room(a.id(), 0);
        room(b.id(), 0);
        Room r3 = room(a.id(), 0);
        roomService.changeStatus(r3.getId(), Room.MAINTENANCE);

        CalendarVO vo = calendarService.calendar(a.id(), DAY);
        assertThat(vo.rooms()).extracting(CalendarRoomVO::roomId).containsExactlyInAnyOrder(
                r1.getId(), r3.getId());
        assertThat(vo.rooms()).extracting(CalendarRoomVO::status)
                .contains(Room.ON_SALE, Room.MAINTENANCE);
    }

    @Test
    @DisplayName("块上带客人姓名与订单状态，日历格子才点得出人")
    void blocksCarryGuestAndStatus() {
        PropertyVO p = property(LocalTime.of(6, 0));
        Room r = room(p.id(), 0);
        var b = bookingService.create(new CreateBookingReq(r.getId(), "王小明", "13800000000",
                DAY.atTime(9, 0), DAY.atTime(12, 0), Booking.CONFIRMED, null));
        bookingService.checkIn(b.id());

        CalendarBlock blk = row(calendarService.calendar(p.id(), DAY), r.getId()).blocks().get(0);
        assertThat(blk.getGuestName()).isEqualTo("王小明");
        assertThat(blk.getBookingStatus()).isEqualTo(Booking.IN_USE);
        assertThat(blk.getBookingNo()).isEqualTo(b.bookingNo());
    }

    /**
     * 审阅第 6 条：只按"与窗口相交"筛，会让两类行从今天消失 ——
     * 昨天该退房却还没退的在住单、以及还没确认的清扫行。
     * 前台看到的是"这间房空着"，开单时后端又提示仍被占用；更要命的是
     * 退房、清扫完成这些按钮都挂在块上，块看不见就等于没有处理入口。
     */
    @Test
    @DisplayName("超时在住与未确认清扫的行即使整段落在窗口之左，也必须出现在今天的日历上")
    void overdueRowsStayVisibleSoTheyCanBeHandled() {
        PropertyVO p = property(LocalTime.of(6, 0));
        Room stale = room(p.id(), 30);
        Room dirty = room(p.id(), 30);
        LocalDateTime yStart = DAY.minusDays(1).atTime(8, 0);
        LocalDateTime yEnd = DAY.minusDays(1).atTime(12, 0);

        var a = bookingService.create(new CreateBookingReq(stale.getId(), "超时客人", null,
                yStart, yEnd, Booking.CONFIRMED, null));
        kernel.checkIn(a.id(), yStart);

        var b = bookingService.create(new CreateBookingReq(dirty.getId(), "待扫客人", null,
                yStart, yEnd, Booking.CONFIRMED, null));
        kernel.checkIn(b.id(), yStart);
        kernel.checkOut(b.id(), yEnd, 30);        // 留下 CLEANING [12:00, 12:30)，整段在 DAY 窗口之左

        assertThat(row(calendarService.calendar(p.id(), DAY), stale.getId()).blocks())
                .as("超时在住必须看得见")
                .singleElement()
                .satisfies(blk -> assertThat(blk.getBookingStatus()).isEqualTo(Booking.IN_USE));

        assertThat(row(calendarService.calendar(p.id(), DAY), dirty.getId()).blocks())
                .as("未确认的清扫行必须看得见，否则一键释放没有入口")
                .singleElement()
                .satisfies(blk -> assertThat(blk.getKind()).isEqualTo("CLEANING"));

        // 确认之后就必须消失 —— 否则"永远显示"会变成另一种噪声
        assertThat(bookingService.finishCleaning(dirty.getId())).isEqualTo(1);
        assertThat(row(calendarService.calendar(p.id(), DAY), dirty.getId()).blocks()).isEmpty();
    }
}
