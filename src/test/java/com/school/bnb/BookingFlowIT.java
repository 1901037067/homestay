package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.school.bnb.booking.BookingKernel;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.ChangeTimesReq;
import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.ExtendReq;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.service.BookingService;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.BookingVO;
import com.school.bnb.vo.FreeSlotVO;
import com.school.bnb.vo.PropertyVO;
import com.school.bnb.vo.RoomTypeVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * W3 预订内核：状态机、改期、加时、入住退房、超时回收、可售区间。
 * 全部走 BookingService（含清扫缓冲解析），所以测的是"前台真实会走的那条路"。
 */
class BookingFlowIT extends AbstractMysqlIT {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 10, 0, 0);

    @Autowired BookingService bookingService;
    @Autowired BookingKernel kernel;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    /** 建一家店 + 一个房型 + 一间房。参数全部显式给出，测试里不藏默认值。 */
    private Room fixture(LocalTime open, LocalTime close, int minHours, int cleanBefore, int cleanAfter) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0), open, close));
        PropertyVO p = propertyService.list().stream()
                .filter(x -> x.name().equals("店" + tag)).findFirst().orElseThrow();
        roomTypeService.save(new RoomTypeReq(null, p.id(), "型" + tag, new BigDecimal("100.00"),
                minHours, new BigDecimal("20.00"), cleanBefore, cleanAfter,
                LocalTime.of(14, 0), LocalTime.of(12, 0)));
        RoomTypeVO t = roomTypeService.list(p.id()).get(0);
        return roomService.save(new RoomReq(null, p.id(), t.id(), "R" + tag, 1, Room.ON_SALE));
    }

    /** 常规房型：可售窗口 08–22，起步 2 小时，无缓冲。 */
    private Room plainRoom() {
        return fixture(LocalTime.of(8, 0), LocalTime.of(22, 0), 2, 0, 0);
    }

    private CreateBookingReq req(Long roomId, LocalDateTime s, LocalDateTime e) {
        return new CreateBookingReq(roomId, "客人" + System.nanoTime(), null, s, e, Booking.CONFIRMED, null);
    }

    private int occupancyCount(Long roomId) {
        Integer n = jdbc.queryForObject("select count(1) from occupancy where room_id = ?", Integer.class, roomId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ 状态机

    @Test
    @DisplayName("完整流转 HELD→CONFIRMED→IN_USE→CHECKED_OUT，退房时释放占用行")
    void fullLifecycle() {
        Room room = plainRoom();
        BookingVO held = bookingService.create(new CreateBookingReq(room.getId(), "预占客", null,
                DAY.withHour(14), DAY.withHour(18), Booking.HELD, 15));
        assertThat(held.status()).isEqualTo(Booking.HELD);
        assertThat(held.holdExpireAt()).isNotNull();
        assertThat(occupancyCount(room.getId())).isEqualTo(1);

        assertThat(bookingService.confirm(held.id()).status()).isEqualTo(Booking.CONFIRMED);
        BookingVO inUse = bookingService.checkIn(held.id());
        assertThat(inUse.status()).isEqualTo(Booking.IN_USE);
        assertThat(inUse.actualCheckIn()).isNotNull();

        BookingVO out = bookingService.checkOut(held.id());
        assertThat(out.status()).isEqualTo(Booking.CHECKED_OUT);
        assertThat(out.actualCheckOut()).isNotNull();
        // 退房即删行：房间立刻可售，不保留"计划中的未来缓冲"
        assertThat(occupancyCount(room.getId())).isZero();
    }

    @Test
    @DisplayName("非法跃迁一律拒绝，且错误信息给出可前往的状态")
    void rejectsIllegalTransitions() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(req(room.getId(), DAY.withHour(14), DAY.withHour(18)));

        // CONFIRMED 不能直接退房
        assertThatThrownBy(() -> bookingService.checkOut(b.id()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BOOKING_ILLEGAL_STATE);
                    assertThat(e.getMessage()).contains("IN_USE");
                });

        BookingVO inUse = bookingService.checkIn(b.id());
        assertThatThrownBy(() -> bookingService.checkIn(inUse.id())).isInstanceOf(BizException.class);
        // IN_USE 不能取消，要先退房
        assertThatThrownBy(() -> bookingService.cancel(inUse.id()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.BOOKING_ILLEGAL_STATE);
        assertThat(occupancyCount(room.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------ 改期

    @Test
    @DisplayName("改期成功：占用行跟着新时间改写，旧时段空出来")
    void changeTimesRewritesOccupancy() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(req(room.getId(), DAY.withHour(14), DAY.withHour(18)));

        BookingVO moved = bookingService.changeTimes(b.id(),
                new ChangeTimesReq(DAY.withHour(9), DAY.withHour(13)));
        assertThat(moved.startTime()).isEqualTo(DAY.withHour(9));
        assertThat(occupancyCount(room.getId())).isEqualTo(1);

        List<FreeSlotVO> afternoon = bookingService.freeSlots(room.getId(), DAY.withHour(14), DAY.withHour(18), null);
        assertThat(afternoon).singleElement().satisfies(s -> {
            assertThat(s.start()).isEqualTo(DAY.withHour(14));
            assertThat(s.minutes()).isEqualTo(240);
        });
    }

    @Test
    @DisplayName("改期撞上别人的时段：拒绝，且旧占用行必须回滚（不能房间和时段两头空）")
    void changeTimesRollsBackOnConflict() {
        Room room = plainRoom();
        BookingVO mine = bookingService.create(req(room.getId(), DAY.withHour(14), DAY.withHour(18)));
        bookingService.create(req(room.getId(), DAY.withHour(9), DAY.withHour(13)));

        assertThatThrownBy(() -> bookingService.changeTimes(mine.id(),
                new ChangeTimesReq(DAY.withHour(10), DAY.withHour(12))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);

        // 关键断言：回滚后我原来的 14–18 还在占着
        assertThat(occupancyCount(room.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(1) from occupancy
                where booking_id = ? and block_start = ? and block_end = ?
                """, Integer.class, mine.id(), DAY.withHour(14), DAY.withHour(18))).isEqualTo(1);
    }

    @Test
    @DisplayName("改期改回自己原来的时段：先删后查，不会被自己挡住")
    void changeTimesToSameSlotAllowed() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(req(room.getId(), DAY.withHour(14), DAY.withHour(18)));

        BookingVO same = bookingService.changeTimes(b.id(),
                new ChangeTimesReq(DAY.withHour(14), DAY.withHour(18)));
        assertThat(same.startTime()).isEqualTo(DAY.withHour(14));
        assertThat(occupancyCount(room.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------ 加时

    @Test
    @DisplayName("加时成功并延长占用块；撞上下一单则拒绝且两边都不掉")
    void extend() {
        Room room = plainRoom();
        BookingVO a = bookingService.create(req(room.getId(), DAY.withHour(14), DAY.withHour(18)));
        bookingService.checkIn(a.id());

        BookingVO extended = bookingService.extend(a.id(), new ExtendReq(DAY.withHour(20)));
        assertThat(extended.endTime()).isEqualTo(DAY.withHour(20));
        assertThat(jdbc.queryForObject("select block_end from occupancy where booking_id = ?",
                LocalDateTime.class, a.id())).isEqualTo(DAY.withHour(20));

        // 20:00–22:00 另开一单（与加时后的 20:00 边界相接，半开区间不算冲突）
        bookingService.create(req(room.getId(), DAY.withHour(20), DAY.withHour(22)));
        assertThatThrownBy(() -> bookingService.extend(a.id(), new ExtendReq(DAY.withHour(21))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
        assertThat(occupancyCount(room.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("加时不能把结束时刻推出可售窗口")
    void extendRespectsSaleWindow() {
        Room room = plainRoom();
        BookingVO a = bookingService.create(req(room.getId(), DAY.withHour(14), DAY.withHour(18)));
        bookingService.checkIn(a.id());

        assertThatThrownBy(() -> bookingService.extend(a.id(), new ExtendReq(DAY.withHour(23))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);
        assertThat(occupancyCount(room.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------ 业务校验

    @Test
    @DisplayName("同一天内要满足起步时长；跨天的全日房模板不受 min_hours 约束")
    void minHoursOnlyConstrainsSameDay() {
        Room room = fixture(LocalTime.of(8, 0), LocalTime.of(22, 0), 3, 0, 0);

        assertThatThrownBy(() -> bookingService.create(req(room.getId(),
                DAY.withHour(14), DAY.withHour(15))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        BookingVO fullDay = bookingService.create(req(room.getId(),
                DAY.withHour(14), DAY.plusDays(1).withHour(12)));
        assertThat(fullDay.endTime()).isEqualTo(DAY.plusDays(1).withHour(12));
    }

    @Test
    @DisplayName("入住时刻必须落在可售窗口内；全日房 14:00 入住天然满足，不需要豁免分支")
    void saleWindowAppliesToCheckInTime() {
        Room room = fixture(LocalTime.of(9, 0), LocalTime.of(21, 0), 2, 0, 0);

        assertThatThrownBy(() -> bookingService.create(req(room.getId(),
                DAY.withHour(7), DAY.withHour(9))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);

        assertThatThrownBy(() -> bookingService.create(req(room.getId(),
                DAY.withHour(20), DAY.withHour(23))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);

        assertThat(bookingService.create(req(room.getId(),
                DAY.withHour(14), DAY.plusDays(1).withHour(12)))).isNotNull();
    }

    @Test
    @DisplayName("维修中的房间不可售")
    void maintenanceRoomRejected() {
        Room room = plainRoom();
        roomService.changeStatus(room.getId(), Room.MAINTENANCE);

        assertThatThrownBy(() -> bookingService.create(req(room.getId(),
                DAY.withHour(14), DAY.withHour(18))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);
    }

    // ------------------------------------------------------------ 超时回收

    @Test
    @DisplayName("预占过期后回收并释放占用；未过期的那笔不动")
    void releasesExpiredHolds() {
        Room room = plainRoom();
        BookingVO expired = bookingService.create(new CreateBookingReq(room.getId(), "过期预占", null,
                DAY.withHour(14), DAY.withHour(18), Booking.HELD, 1));
        BookingVO alive = bookingService.create(new CreateBookingReq(room.getId(), "有效预占", null,
                DAY.withHour(9), DAY.withHour(12), Booking.HELD, 600));
        assertThat(occupancyCount(room.getId())).isEqualTo(2);

        // 取"30 分钟后"：已过期的落在回收窗口里，600 分钟那笔还在保留期内
        int n = kernel.releaseExpiredHolds(LocalDateTime.now().plusMinutes(30));
        assertThat(n).isGreaterThanOrEqualTo(1);
        assertThat(kernel.require(expired.id()).getStatus()).isEqualTo(Booking.RELEASED);
        assertThat(kernel.require(alive.id()).getStatus()).isEqualTo(Booking.HELD);
        assertThat(occupancyCount(room.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("已确认的预占不会被回收任务误删（条件更新只认 HELD）")
    void confirmedHoldSurvivesRelease() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "秒确认", null,
                DAY.withHour(14), DAY.withHour(18), Booking.HELD, 1));
        bookingService.confirm(b.id());

        kernel.releaseExpiredHolds(LocalDateTime.now().plusMinutes(30));

        assertThat(kernel.require(b.id()).getStatus()).isEqualTo(Booking.CONFIRMED);
        assertThat(occupancyCount(room.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------ 可售区间

    @Test
    @DisplayName("空闲区间 = 窗口减去占用块（含缓冲）；minHours 只过滤不改边界")
    void freeSlotsSubtractBlocks() {
        Room room = fixture(LocalTime.of(8, 0), LocalTime.of(22, 0), 3, 0, 45);
        // 占 11:00–14:00，退房缓冲 45 分钟 → block 到 14:45
        bookingService.create(req(room.getId(), DAY.withHour(11), DAY.withHour(14)));

        List<FreeSlotVO> slots = bookingService.freeSlots(room.getId(), DAY.withHour(8), DAY.withHour(18), null);
        assertThat(slots).hasSize(2);
        assertThat(slots.get(0).start()).isEqualTo(DAY.withHour(8));
        assertThat(slots.get(0).end()).isEqualTo(DAY.withHour(11));
        assertThat(slots.get(1).start()).isEqualTo(DAY.withHour(14).plusMinutes(45));
        assertThat(slots.get(1).end()).isEqualTo(DAY.withHour(18));
        assertThat(slots.get(1).minutes()).isEqualTo(195);

        // 要求 4 小时以上，两段都不满足
        assertThat(bookingService.freeSlots(room.getId(), DAY.withHour(8), DAY.withHour(18), 4)).isEmpty();
    }

    @Test
    @DisplayName("可售房间查询：被占的房不出现，取消后回到候选里")
    void availableRooms() {
        Room a = plainRoom();
        Room b = plainRoom();

        assertThat(bookingService.availableRooms(null, DAY.withHour(14), DAY.withHour(18), null))
                .extracting("id").contains(a.getId(), b.getId());

        BookingVO taken = bookingService.create(req(a.getId(), DAY.withHour(14), DAY.withHour(18)));
        assertThat(bookingService.availableRooms(null, DAY.withHour(14), DAY.withHour(18), null))
                .extracting("id").doesNotContain(a.getId());

        bookingService.cancel(taken.id());
        assertThat(bookingService.availableRooms(null, DAY.withHour(14), DAY.withHour(18), null))
                .extracting("id").contains(a.getId());
    }
}
