package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.school.bnb.booking.BookingKernel;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 第二轮审阅的 5 条修复，逐条对应一个测试。
 *
 * <p>其中"在住期间必须持续挡住后续时段"这一条，是对上一轮我一句<b>错误结论</b>的更正：
 * 我当时说"超时未退房会让房间一直挡着，保守且正确"—— 实际不会，
 * 冲突检测只比固定的 block_end，客人没走但计划时间已过，新单照样能开出去。
 */
class OccupancyTruthIT extends AbstractMysqlIT {

    /**
     * 基准日刻意取<b>昨天</b>：超时未退房的判定依赖 {@code now() >= block_end}，
     * 用今天会在凌晨跑挂（那时 12:00 还没到，IN_USE 条款不触发，测试假失败）。
     */
    private static final LocalDateTime TODAY = LocalDateTime.now().minusDays(1).toLocalDate().atStartOfDay();

    @Autowired BookingService bookingService;
    @Autowired BookingKernel kernel;
    @Autowired PropertyService propertyService;
    @Autowired RoomTypeService roomTypeService;
    @Autowired RoomService roomService;
    @Autowired JdbcTemplate jdbc;

    private Room room(int minHours, int cleanBefore, int cleanAfter) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "型" + tag, new BigDecimal("100.00"),
                minHours, new BigDecimal("20.00"), cleanBefore, cleanAfter,
                LocalTime.of(14, 0), LocalTime.of(12, 0)));
        Long tid = roomTypeService.list(pid).get(0).id();
        return roomService.save(new RoomReq(null, pid, tid, "R" + tag, 1, Room.ON_SALE));
    }

    private BookingVO book(Long roomId, LocalDateTime s, LocalDateTime e, String type, String rid) {
        return bookingService.create(new CreateBookingReq(roomId, "客人" + System.nanoTime(), null,
                s, e, Booking.CONFIRMED, null, type, rid));
    }

    private int occupancyRows(Long roomId) {
        Integer n = jdbc.queryForObject("select count(1) from occupancy where room_id = ?", Integer.class, roomId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------ ① 在住必须持续挡房

    @Test
    @DisplayName("① 客人超时未退房：计划时段之后的新单必须被挡住")
    void inUseGuestBlocksEverythingAfterCheckIn() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(9));

        // 12:00 计划离店 + 30 分缓冲 = 12:30。客人没走，13:00 之后的单不能再开。
        assertThatThrownBy(() -> book(r.getId(), TODAY.withHour(13), TODAY.withHour(16), "HOURLY", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("在住期间 block_end 被顶到退房才知道，13:00 的新单必须冲突")
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
    }

    @Test
    @DisplayName("① 退房之后才恢复可售（哨兵值必须被撤销，否则房间永久锁死）")
    void checkOutRestoresSellability() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(9));
        assertThat(occupancyRows(r.getId())).isEqualTo(1);

        kernel.checkOut(b.id(), TODAY.withHour(12));
        assertThat(occupancyRows(r.getId())).as("退房即释放").isZero();

        // 13:00 现在可以开了
        assertThat(book(r.getId(), TODAY.withHour(13), TODAY.withHour(16), "HOURLY", null)).isNotNull();
    }

    @Test
    @DisplayName("① 提前入住：实际入住时刻之前的空档也要占住，否则会被别人开走")
    void earlyCheckInBlocksTheGapBeforePlannedStart() {
        Room r = room(2, 0, 30);
        // 计划 14:00 入住，客人 10:00 就到了，前台让他先住
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(10));

        assertThatThrownBy(() -> book(r.getId(), TODAY.withHour(10), TODAY.withHour(12), "HOURLY", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("10:00–14:00 这段虽然不在计划里，但人已经住进去了")
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
    }

    // ------------------------------------- ② 可售查询要算上新单自己的缓冲

    @Test
    @DisplayName("② 可售查询与下单同口径：已有块到 12:45、新单入住前缓冲 30 分钟 → 不该返回可售")
    void availableRoomsAccountsForOwnBuffer() {
        // 该房型入住前缓冲 30 分钟
        Room r = room(2, 30, 0);
        // 占 11:00–12:00，退房缓冲 0 → block 到 12:00
        book(r.getId(), TODAY.withHour(10), TODAY.withHour(12), "HOURLY", null);

        // 新单 12:00 入住：自己的入住前缓冲把它推到 11:30 → 与 10:00–12:00 冲突
        assertThat(bookingService.availableRooms(null, TODAY.withHour(12), TODAY.withHour(14), null,
                com.school.bnb.booking.SaleType.HOURLY))
                .as("查询必须和内核一样把新单的入住前缓冲算进来")
                .extracting("id").doesNotContain(r.getId());

        // 13:00 入住：缓冲推到 12:30，不冲突
        assertThat(bookingService.availableRooms(null, TODAY.withHour(13), TODAY.withHour(15), null,
                com.school.bnb.booking.SaleType.HOURLY))
                .extracting("id").contains(r.getId());
    }

    // ------------------------------------------------- ③ 加时只能变长

    @Test
    @DisplayName("③ 加时到更早的时间必须被拒（这条检查曾在重写时被删掉）")
    void extendMustActuallyLengthen() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(14));

        assertThatThrownBy(() -> bookingService.extend(b.id(), new ExtendReq(TODAY.withHour(17))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("17:00 早于原 18:00，那是缩短不是加时")
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        // 原时段必须没被改动
        assertThat(kernel.require(b.id()).getEndTime()).isEqualTo(TODAY.withHour(18));
    }

    // --------------------------------------------- ④ 全日房可以加时

    @Test
    @DisplayName("④ 全日房延迟退房：加时不该被'区间必须等于模板'挡住")
    void fullDayBookingCanBeExtended() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.plusDays(1).withHour(12), "FULL_DAY", null);
        kernel.checkIn(b.id(), TODAY.withHour(14));

        BookingVO later = bookingService.extend(b.id(), new ExtendReq(TODAY.plusDays(1).withHour(14)));
        assertThat(later.endTime()).isEqualTo(TODAY.plusDays(1).withHour(14));
    }

    // --------------------------------------- ⑤ 同标识不同内容要拒

    @Test
    @DisplayName("⑤ 同一 requestId 带不同时段：明确拒绝，不能回放旧单让前端误报成功")
    void sameRequestIdDifferentContentIsRejected() {
        Room r = room(2, 0, 0);
        String rid = "REQ-" + System.nanoTime();

        BookingVO first = bookingService.create(new CreateBookingReq(r.getId(), "同一请求", null,
                TODAY.withHour(9), TODAY.withHour(12), Booking.CONFIRMED, null, "HOURLY", rid));

        // 第一次提交成功但响应超时，前台在同一个弹窗里把时段改成 13:00–16:00 重试
        assertThatThrownBy(() -> bookingService.create(new CreateBookingReq(r.getId(), "同一请求", null,
                TODAY.withHour(13), TODAY.withHour(16), Booking.CONFIRMED, null, "HOURLY", rid)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);

        // 旧单没被动过，也没多开一张
        assertThat(kernel.require(first.id()).getStartTime()).isEqualTo(TODAY.withHour(9));
        assertThat(occupancyRows(r.getId())).isEqualTo(1);
    }
}
