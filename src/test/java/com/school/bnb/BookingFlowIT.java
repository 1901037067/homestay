package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.school.bnb.booking.BookingKernel;
import com.school.bnb.booking.SaleType;
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
                minHours, new BigDecimal("20.00"), null, cleanBefore, cleanAfter,
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
        // 带 45 分钟退房缓冲，才能验到"退房转清扫"这一步
        Room room = fixture(LocalTime.of(8, 0), LocalTime.of(22, 0), 2, 0, 45);
        BookingVO held = bookingService.create(new CreateBookingReq(room.getId(), "预占客", null,
                DAY.withHour(14), DAY.withHour(18), Booking.HELD, 15));
        assertThat(held.status()).isEqualTo(Booking.HELD);
        assertThat(held.holdExpireAt()).isNotNull();
        assertThat(occupancyCount(room.getId())).isEqualTo(1);

        assertThat(bookingService.confirm(held.id()).status()).isEqualTo(Booking.CONFIRMED);
        Booking inUse = kernel.checkIn(held.id(), held.startTime());
        assertThat(inUse.getStatus()).isEqualTo(Booking.IN_USE);
        assertThat(inUse.getActualCheckIn()).isNotNull();

        BookingVO out = bookingService.checkOut(held.id());
        assertThat(out.status()).isEqualTo(Booking.CHECKED_OUT);
        assertThat(out.actualCheckOut()).isNotNull();
        // 退房不是删空：留下 45 分钟清扫占用，保洁点完成才真正可售
        assertThat(occupancyCount(room.getId())).as("清扫块").isEqualTo(1);
        assertThat(bookingService.finishCleaning(room.getId())).isEqualTo(1);
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

        Booking inUse = kernel.checkIn(b.id(), b.startTime());
        assertThatThrownBy(() -> kernel.checkIn(inUse.getId(), inUse.getStartTime())).isInstanceOf(BizException.class);
        // IN_USE 不能取消，要先退房
        assertThatThrownBy(() -> bookingService.cancel(inUse.getId()))
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
        kernel.checkIn(a.id(), a.startTime());

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
        kernel.checkIn(a.id(), a.startTime());

        assertThatThrownBy(() -> bookingService.extend(a.id(), new ExtendReq(DAY.withHour(23))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);
        assertThat(occupancyCount(room.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------ 业务校验

    @Test
    @DisplayName("起步时长按显式 saleType 判定：钟点受约束，全日房模板不受；钟点不许跨天")
    void minHoursFollowsDeclaredSaleType() {
        Room room = fixture(LocalTime.of(8, 0), LocalTime.of(22, 0), 3, 0, 0);

        assertThatThrownBy(() -> bookingService.create(req(room.getId(),
                DAY.withHour(14), DAY.withHour(15))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        // 审阅抓出来的洞：22:00 → 次日 00:30 只有 2.5 小时，靠"跨天"就能绕过起步时长。
        // 现在它按钟点会被直接拒（跨天），要买跨天必须显式声明全日房。
        assertThatThrownBy(() -> bookingService.create(req(room.getId(),
                DAY.withHour(21), DAY.plusDays(1).withMinute(30))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        BookingVO fullDay = bookingService.create(new CreateBookingReq(room.getId(), "全日客", null,
                DAY.withHour(14), DAY.plusDays(1).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        assertThat(fullDay.endTime()).isEqualTo(DAY.plusDays(1).withHour(12));
    }

    @Test
    @DisplayName("声明成全日房但区间不等于房型模板，照样拒")
    void fullDayMustMatchTemplate() {
        Room room = fixture(LocalTime.of(8, 0), LocalTime.of(22, 0), 3, 0, 0);

        assertThatThrownBy(() -> bookingService.create(new CreateBookingReq(room.getId(), "假全日", null,
                DAY.withHour(10), DAY.plusDays(1).withHour(2), Booking.CONFIRMED, null, "FULL_DAY", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);
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

        assertThat(bookingService.create(new CreateBookingReq(room.getId(), "全日客", null,
                DAY.withHour(14), DAY.plusDays(1).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null)))
                .isNotNull();
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

        assertThat(bookingService.availableRooms(null, DAY.withHour(14), DAY.withHour(18), null, SaleType.HOURLY))
                .extracting("id").contains(a.getId(), b.getId());

        BookingVO taken = bookingService.create(req(a.getId(), DAY.withHour(14), DAY.withHour(18)));
        assertThat(bookingService.availableRooms(null, DAY.withHour(14), DAY.withHour(18), null, SaleType.HOURLY))
                .extracting("id").doesNotContain(a.getId());

        bookingService.cancel(taken.id());
        assertThat(bookingService.availableRooms(null, DAY.withHour(14), DAY.withHour(18), null, SaleType.HOURLY))
                .extracting("id").contains(a.getId());
    }

    /**
     * 审阅第 2 条的直接回归：确认与超时回收同时发生时，
     * 绝不允许出现"状态是 CONFIRMED 但占用行已被删"的订单 —— 那等于房间被白占了。
     *
     * <p>修法是把状态判定交给带原状态的条件更新：回收只认 HELD，确认只认 HELD，
     * 两者抢同一行，必然只有一个成功。
     */
    @Test
    @DisplayName("确认与超时回收并发：状态与占用行必须始终一致")
    void confirmRacesHoldRelease() throws Exception {
        for (int i = 0; i < 15; i++) {
            Room room = plainRoom();
            BookingVO held = bookingService.create(new CreateBookingReq(room.getId(), "抢确认", null,
                    DAY.withHour(14), DAY.withHour(18), Booking.HELD, 1, null, null));

            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var gate = new java.util.concurrent.CountDownLatch(1);
            Runnable confirm = () -> {
                try {
                    gate.await();
                    bookingService.confirm(held.id());
                } catch (Exception ignored) {
                    // 被回收抢先就是预期结果之一
                }
            };
            Runnable release = () -> {
                try {
                    gate.await();
                    kernel.releaseExpiredHolds(java.time.LocalDateTime.now().plusMinutes(30));
                } catch (Exception ignored) {
                }
            };
            var f1 = pool.submit(confirm);
            var f2 = pool.submit(release);
            gate.countDown();
            f1.get(60, java.util.concurrent.TimeUnit.SECONDS);
            f2.get(60, java.util.concurrent.TimeUnit.SECONDS);
            pool.shutdownNow();

            String status = kernel.require(held.id()).getStatus();
            int rows = occupancyCount(room.getId());
            assertThat(rows)
                    .as("第 %d 轮 status=%s：CONFIRMED 必须有占用行，RELEASED 必须没有", i, status)
                    .isEqualTo(Booking.CONFIRMED.equals(status) ? 1 : 0);
        }
    }

    // ------------------------------------------------------------ 全日房连住

    @Test
    @DisplayName("全日房连住三晚：一条连续占用，中间插不进别人的单")
    void fullDayMultiNightOccupiesContiguously() {
        Room room = plainRoom();                       // 模板 14:00 → 次日 12:00
        LocalDateTime start = DAY.withHour(14);
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "连住客", null,
                start, DAY.plusDays(3).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));

        assertThat(occupancyCount(room.getId()))
                .as("连住是一整段，不是三行 —— 中间 12:00–14:00 客人房没退").isEqualTo(1);
        LocalDateTime[] block = jdbc.queryForObject(
                "select block_start, block_end from occupancy where booking_id = ?",
                (rs, n) -> new LocalDateTime[]{rs.getTimestamp(1).toLocalDateTime(),
                        rs.getTimestamp(2).toLocalDateTime()},
                b.id());
        assertThat(block[0]).as("没有清扫缓冲时占用起点就是入住时刻").isEqualTo(start);
        assertThat(block[1]).isEqualTo(DAY.plusDays(3).withHour(12));
    }

    @Test
    @DisplayName("连住的晚数与覆盖时长都进快照；不是整晚的区间一律拒")
    void multiNightSnapshotsNightsAndRejectsPartialNight() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "三晚客", null,
                DAY.withHour(14), DAY.plusDays(3).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        assertThat(jdbc.queryForObject("select nights from booking where id = ?", Integer.class, b.id()))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select covered_minutes from booking where id = ?", Integer.class, b.id()))
                .as("覆盖 = 24h×2 + 一晚模板 22h").isEqualTo(1440 * 2 + 1320);

        // 终点没落在模板结束时刻上 —— 拼不出整数晚，必须拒（否则计费晚数与区间自相矛盾）
        assertThatThrownBy(() -> bookingService.create(new CreateBookingReq(room.getId(), "半晚客", null,
                DAY.withHour(14), DAY.plusDays(2).withHour(18), Booking.CONFIRMED, null, "FULL_DAY", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        // 起点不是模板起点也不行
        assertThatThrownBy(() -> bookingService.create(new CreateBookingReq(room.getId(), "晚起客", null,
                DAY.withHour(15), DAY.plusDays(1).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        // 晚数上限：前端限到 60，但 API 是公开面，手滑开一张一年多的单得由后端挡
        assertThatThrownBy(() -> bookingService.create(new CreateBookingReq(room.getId(), "超长客", null,
                DAY.withHour(14), DAY.plusDays(400).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);
    }

    @Test
    @DisplayName("连住期间再开一张钟点单必须被挡 —— 占用行只有一条不代表中间是空的")
    void multiNightBlocksEveryInteriorSlot() {
        Room room = plainRoom();
        bookingService.create(new CreateBookingReq(room.getId(), "连住客", null,
                DAY.withHour(14), DAY.plusDays(3).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));

        assertThatThrownBy(() -> bookingService.create(new CreateBookingReq(room.getId(), "插单客", null,
                DAY.plusDays(1).withHour(15), DAY.plusDays(1).withHour(18), Booking.CONFIRMED, null,
                "HOURLY", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
    }

    @Test
    @DisplayName("改期缩短晚数必须重写快照 —— 否则三晚改成一晚还照三晚收钱")
    void rescheduleRewritesNightsSnapshot() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "改期连住", null,
                DAY.withHour(14), DAY.plusDays(3).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        assertThat(jdbc.queryForObject("select nights from booking where id = ?", Integer.class, b.id()))
                .isEqualTo(3);

        bookingService.changeTimes(b.id(), new ChangeTimesReq(
                DAY.plusDays(5).withHour(14), DAY.plusDays(6).withHour(12)));

        assertThat(jdbc.queryForObject("select nights from booking where id = ?", Integer.class, b.id()))
                .as("改成只住一晚，快照必须跟着改").isEqualTo(1);
        assertThat(jdbc.queryForObject("select covered_minutes from booking where id = ?", Integer.class, b.id()))
                .isEqualTo(1320);
    }

    @Test
    @DisplayName("加一晚：在住的单也能续，nights 与覆盖时长跟着长，占用仍是一条")
    void addNightWorksForInUseStay() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "续住客", null,
                DAY.withHour(14), DAY.plusDays(2).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        // 先入住 —— 这个状态正是"改期走不了"的那个（改期只收未入住）
        kernel.checkIn(b.id(), DAY.withHour(14));

        BookingVO after = bookingService.addNight(b.id());

        assertThat(after.endTime()).as("离店延后一天").isEqualTo(DAY.plusDays(3).withHour(12));
        assertThat(after.nights()).as("2 晚 → 3 晚").isEqualTo(3);
        assertThat(jdbc.queryForObject("select covered_minutes from booking where id = ?", Integer.class, b.id()))
                .as("覆盖时长要多出一整晚的 24 小时").isEqualTo(1440 * 2 + 1320);
        assertThat(occupancyCount(room.getId())).as("仍是一条连续占用，不是两行").isEqualTo(1);
    }

    @Test
    @DisplayName("加一晚撞上后面的单要拒，占用与快照都不能变")
    void addNightRejectsConflictAndRollsBack() {
        Room room = plainRoom();
        BookingVO a = bookingService.create(new CreateBookingReq(room.getId(), "原住客", null,
                DAY.withHour(14), DAY.plusDays(2).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        // 后面已经卖出去的时段：加一晚会把 a 顶到这段上
        bookingService.create(new CreateBookingReq(room.getId(), "后来的", null,
                DAY.plusDays(3).withHour(11), DAY.plusDays(3).withHour(18), Booking.CONFIRMED, null,
                "HOURLY", null));
        kernel.checkIn(a.id(), DAY.withHour(14));

        assertThatThrownBy(() -> bookingService.addNight(a.id()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);

        assertThat(kernel.require(a.id()).getEndTime()).as("被拒之后离店时刻不能变")
                .isEqualTo(DAY.plusDays(2).withHour(12));
        assertThat(jdbc.queryForObject("select nights from booking where id = ?", Integer.class, a.id()))
                .isEqualTo(2);
        assertThat(occupancyCount(room.getId())).as("自己的占用删掉后随事务回滚回来").isEqualTo(2);
    }

    @Test
    @DisplayName("先加时到非整晚终点（15:00）再加一晚：必须拒，快照不能被兜底写成 1 晚")
    void addNightAfterLateCheckoutIsRejected() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "加时后", null,
                DAY.withHour(14), DAY.plusDays(2).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        kernel.checkIn(b.id(), DAY.withHour(14));
        // 加时只按超时收、不动 nights 快照，但区间已经不落在整晚终点上了。
        // 此时再 +1 天会得到"次日 15:00"，fullDayNights 返回 0 ——
        // 旧代码会让 PricingFrames 走"非整数晚兜底"，把快照写成 1 晚/1320 分钟
        bookingService.extend(b.id(), new ExtendReq(DAY.plusDays(2).withHour(15)));

        assertThatThrownBy(() -> bookingService.addNight(b.id()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);

        assertThat(kernel.require(b.id()).getEndTime()).as("被拒之后离店时刻不能变")
                .isEqualTo(DAY.plusDays(2).withHour(15));
        assertThat(jdbc.queryForObject("select nights from booking where id = ?", Integer.class, b.id()))
                .as("快照仍是 2 晚，不能被兜底改成 1 晚").isEqualTo(2);
    }

    @Test
    @DisplayName("加时正好 24 小时落回整晚终点，再加一晚应得到 3 晚")
    void addNightAfterWholeDayExtensionGivesThreeNights() {
        Room room = plainRoom();
        BookingVO b = bookingService.create(new CreateBookingReq(room.getId(), "加时一天", null,
                DAY.withHour(14), DAY.plusDays(1).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        kernel.checkIn(b.id(), DAY.withHour(14));
        bookingService.extend(b.id(), new ExtendReq(DAY.plusDays(2).withHour(12)));   // +24h，落回整晚终点

        BookingVO after = bookingService.addNight(b.id());

        assertThat(after.endTime()).isEqualTo(DAY.plusDays(3).withHour(12));
        assertThat(after.nights()).as("1 晚 → 加时一晚 → 再加一晚 = 3 晚").isEqualTo(3);
        assertThat(jdbc.queryForObject("select covered_minutes from booking where id = ?", Integer.class, b.id()))
                .isEqualTo(1440 * 2 + 1320);
    }

    @Test
    @DisplayName("已取消的单加晚：先判状态，别让冲突检测抢走错误码")
    void addNightOnCancelledBookingReportsStateNotConflict() {
        Room room = plainRoom();
        BookingVO first = bookingService.create(new CreateBookingReq(room.getId(), "先订后取消", null,
                DAY.withHour(14), DAY.plusDays(1).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));
        bookingService.cancel(first.id());
        // 此刻库里没有这张单的占用，但新单会占住同一区间 —— 旧实现会先去跑冲突检测
        bookingService.create(new CreateBookingReq(room.getId(), "后订的", null,
                DAY.withHour(14), DAY.plusDays(1).withHour(12), Booking.CONFIRMED, null, "FULL_DAY", null));

        assertThatThrownBy(() -> bookingService.addNight(first.id()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("同一张已取消的单，有没有别人占着都该回同一个状态错，而不是 3003")
                .isEqualTo(ErrorCode.BOOKING_ILLEGAL_STATE);
    }

    // ------------------------------------------------------------ 列表筛选

    @Test
    @DisplayName("订单列表按入住时刻筛，半开区间 [from, to)：卡在边界上的那一单只归一天")
    void listFiltersByStartTimeHalfOpen() {
        Room r = plainRoom();
        LocalDateTime d1 = LocalDateTime.of(2026, 11, 10, 9, 0);
        LocalDateTime d2 = LocalDateTime.of(2026, 11, 11, 9, 0);
        LocalDateTime d3 = LocalDateTime.of(2026, 11, 12, 9, 0);
        for (LocalDateTime d : List.of(d1, d2, d3)) {
            bookingService.create(req(r.getId(), d, d.plusHours(3)));
        }

        assertThat(starts(r.getId(), d1.toLocalDate().atStartOfDay(), d2.toLocalDate().atStartOfDay()))
                .as("11-10 那天只该有 d1").containsExactly(d1);
        assertThat(starts(r.getId(), d2.toLocalDate().atStartOfDay(), d3.toLocalDate().atStartOfDay()))
                .as("11-11 那天只该有 d2").containsExactly(d2);
        assertThat(starts(r.getId(), d2, d2))
                .as("to 是开区间：区间空了就查不到任何单").isEmpty();
        assertThat(starts(r.getId(), d2, null))
                .as("只给 from 时往后不限").containsExactly(d2, d3);
    }

    /** 按筛选条件取回来的入住时刻，升序 —— 本组只验筛选，不关心列表的 id 倒序 */
    private List<LocalDateTime> starts(Long roomId, LocalDateTime from, LocalDateTime to) {
        return bookingService.list(1, 50, null, roomId, null, from, to).getRecords().stream()
                .map(BookingVO::startTime).sorted().toList();
    }
}
