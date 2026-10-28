package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.booking.BookingKernel;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.service.PropertyService;
import com.school.bnb.service.RoomService;
import com.school.bnb.service.RoomTypeService;
import com.school.bnb.vo.PropertyVO;
import com.school.bnb.vo.RoomTypeVO;
import com.school.bnb.vo.RoomVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 房源域（W2）：CRUD + 实施计划 2.6 的删除守卫。 */
class RoomCatalogIT extends AbstractMysqlIT {

    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired BookingKernel kernel;

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 12, 1, 0, 0);

    private PropertyVO newProperty() {
        String suffix = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "测试店" + suffix, "某路 1 号",
                LocalTime.of(6, 0), LocalTime.of(8, 0), LocalTime.of(22, 0)));
        return propertyService.list().stream()
                .filter(p -> p.name().equals("测试店" + suffix))
                .findFirst().orElseThrow();
    }

    private RoomTypeVO newRoomType(Long propertyId, int cleanAfter) {
        roomTypeService.save(new RoomTypeReq(null, propertyId, "大床房-" + System.nanoTime(),
                new BigDecimal("200.00"), 3, new BigDecimal("30.00"), 0, cleanAfter,
                LocalTime.of(14, 0), LocalTime.of(12, 0)));
        return roomTypeService.list(propertyId).stream()
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .findFirst().orElseThrow();
    }

    private Room newRoom(Long propertyId, Long roomTypeId, String roomNo) {
        return roomService.save(new RoomReq(null, propertyId, roomTypeId, roomNo, 1, Room.ON_SALE));
    }

    @Test
    @DisplayName("民宿 → 房型 → 房间 三级各能增改查，且列表带出上级名称")
    void crudAcrossThreeLevels() {
        PropertyVO p = newProperty();
        assertThat(p.roomTypeCount()).isZero();

        RoomTypeVO t = newRoomType(p.id(), 45);
        assertThat(t.propertyName()).isEqualTo(p.name());

        Room r = newRoom(p.id(), t.id(), "801");
        Page<RoomVO> page = roomService.page(1, 20, p.id(), null, null, null);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).roomTypeName()).isEqualTo(t.name());
        assertThat(page.getRecords().get(0).propertyName()).isEqualTo(p.name());

        roomService.save(new RoomReq(r.getId(), p.id(), t.id(), "801", 2, Room.MAINTENANCE));
        assertThat(roomService.require(r.getId()).getFloorNo()).isEqualTo(2);
        assertThat(roomService.require(r.getId()).getStatus()).isEqualTo(Room.MAINTENANCE);

        assertThat(roomTypeService.list(p.id()).get(0).roomCount()).isEqualTo(1);
        assertThat(propertyService.list().stream().filter(x -> x.id().equals(p.id()))
                .findFirst().orElseThrow().roomCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("房间有历史订单时禁止删除，报 ROOM_HAS_HISTORY；无订单时可删")
    void deleteGuardedByBookingHistory() {
        PropertyVO p = newProperty();
        RoomTypeVO t = newRoomType(p.id(), 0);
        Room used = newRoom(p.id(), t.id(), "901");
        Room empty = newRoom(p.id(), t.id(), "902");

        kernel.createBooking(new CreateBookingCmd(used.getId(), "住客", null,
                DAY.withHour(14), DAY.withHour(18), 0, 0, Booking.CONFIRMED, null));

        assertThatThrownBy(() -> roomService.delete(used.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_HAS_HISTORY);

        roomService.delete(empty.getId());
        assertThat(roomService.page(1, 20, p.id(), null, null, null).getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("取消订单后占用行被删，但订单行仍在 —— 房间依然不可物理删除")
    void cancelledBookingStillBlocksDelete() {
        PropertyVO p = newProperty();
        RoomTypeVO t = newRoomType(p.id(), 0);
        Room r = newRoom(p.id(), t.id(), "903");

        Booking b = kernel.createBooking(new CreateBookingCmd(r.getId(), "住客", null,
                DAY.withHour(14), DAY.withHour(18), 0, 0, Booking.CONFIRMED, null));
        kernel.cancel(b.getId());

        assertThatThrownBy(() -> roomService.delete(r.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_HAS_HISTORY);
    }

    @Test
    @DisplayName("有下级数据时禁止删除民宿与房型")
    void parentsGuardedAgainstDelete() {
        PropertyVO p = newProperty();
        RoomTypeVO t = newRoomType(p.id(), 30);

        assertThatThrownBy(() -> propertyService.delete(p.id()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.HAS_CHILDREN);

        roomTypeService.delete(t.id());
        propertyService.delete(p.id());
    }

    @Test
    @DisplayName("过滤与分页：按状态筛、按房号搜、翻页不重不漏")
    void filtersAndPaging() {
        PropertyVO p = newProperty();
        RoomTypeVO t = newRoomType(p.id(), 0);
        for (int i = 0; i < 25; i++) {
            newRoom(p.id(), t.id(), String.format("1%02d", i));
        }
        roomService.save(new RoomReq(
                roomService.page(1, 50, p.id(), null, null, "103").getRecords().get(0).id(),
                p.id(), t.id(), "103", 1, Room.OFFLINE));

        assertThat(roomService.page(1, 20, p.id(), null, Room.OFFLINE, null).getRecords()).hasSize(1);
        assertThat(roomService.page(1, 20, p.id(), null, null, "107").getRecords()).hasSize(1);
        // 房号 100~124 里含 "11" 的是 110~119，共 10 间
        assertThat(roomService.page(1, 20, p.id(), null, null, "11").getTotal()).isEqualTo(10);

        List<RoomVO> first = roomService.page(1, 10, p.id(), null, null, null).getRecords();
        List<RoomVO> second = roomService.page(2, 10, p.id(), null, null, null).getRecords();
        List<RoomVO> third = roomService.page(3, 10, p.id(), null, null, null).getRecords();
        List<RoomVO> fourth = roomService.page(4, 10, p.id(), null, null, null).getRecords();
        assertThat(first).hasSize(10);
        assertThat(second).hasSize(10);
        assertThat(third).hasSize(5);
        assertThat(fourth).isEmpty();

        List<String> all = new java.util.ArrayList<>();
        all.addAll(first.stream().map(RoomVO::roomNo).toList());
        all.addAll(second.stream().map(RoomVO::roomNo).toList());
        all.addAll(third.stream().map(RoomVO::roomNo).toList());
        assertThat(all).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("房型不能挂到别的民宿下")
    void roomTypeMustBelongToProperty() {
        PropertyVO a = newProperty();
        PropertyVO b = newProperty();
        RoomTypeVO ta = newRoomType(a.id(), 0);

        assertThatThrownBy(() -> roomService.save(new RoomReq(null, b.id(), ta.id(), "701", 1, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }
}
