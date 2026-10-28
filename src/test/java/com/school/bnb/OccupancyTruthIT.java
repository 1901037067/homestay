package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.school.bnb.booking.BookingKernel;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.ExtendReq;
import com.school.bnb.dto.PropertyReq;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.dto.RoomTypeReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.mapper.RoomMapper;
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
    @Autowired RoomMapper roomMapper;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private Room room(int minHours, int cleanBefore, int cleanAfter) {
        String tag = String.valueOf(System.nanoTime());
        propertyService.save(new PropertyReq(null, "店" + tag, null, LocalTime.of(6, 0),
                LocalTime.of(0, 0), LocalTime.of(23, 59)));
        Long pid = propertyService.list().stream().filter(p -> p.name().equals("店" + tag))
                .findFirst().orElseThrow().id();
        roomTypeService.save(new RoomTypeReq(null, pid, "型" + tag, new BigDecimal("100.00"),
                minHours, new BigDecimal("20.00"), null, cleanBefore, cleanAfter,
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
    @DisplayName("① 退房转清扫：ROOM 行没了但留下 CLEANING 行，保洁点完成才真正可售")
    void checkOutHandsOverToCleaning() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(9));
        assertThat(occupancyRows(r.getId())).isEqualTo(1);

        kernel.checkOut(b.id(), TODAY.withHour(12), 30);
        assertThat(rowsOfKind(r.getId(), "ROOM")).as("房时间占用已释放").isZero();
        assertThat(rowsOfKind(r.getId(), "CLEANING")).as("转成清扫占用").isEqualTo(1);

        // 清扫期间 12:00–13:00 这一格还不能卖（12:00 起被清扫占住）
        assertThatThrownBy(() -> book(r.getId(), TODAY.withHour(12), TODAY.withHour(14), "HOURLY", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);

        assertThat(bookingService.finishCleaning(r.getId())).isEqualTo(1);
        assertThat(occupancyRows(r.getId())).isZero();
        assertThat(book(r.getId(), TODAY.withHour(12), TODAY.withHour(14), "HOURLY", null)).isNotNull();
    }

    private int rowsOfKind(Long roomId, String kind) {
        Integer n = jdbc.queryForObject(
                "select count(1) from occupancy where room_id = ? and kind = ?",
                Integer.class, roomId, kind);
        return n == null ? 0 : n;
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

    // ------------------------- ⑤ 同标识不同内容要拒（续：售卖方式也算内容）

    @Test
    @DisplayName("⑤ 同一 requestId 但售卖方式不同：也必须拒（saleType 决定计费口径）")
    void sameRequestIdDifferentSaleTypeIsRejected() {
        Room r = room(2, 0, 0);
        String rid = "REQ-SALE-" + System.nanoTime();
        LocalDateTime start = TODAY.withHour(14);
        LocalDateTime end = TODAY.plusDays(1).withHour(12);
        // 直接调内核：这条路径用 BookingService 是够不着的 —— 14:00→次日12:00 只可能合法地是
        // FULL_DAY（钟点不许跨天），所以"同 requestId 改售卖方式"只会出现在更下游的调用者身上。
        // 比对逻辑就在内核里，测试也放在这一层
        kernel.createBooking(new CreateBookingCmd(r.getId(), "同一请求", null, start, end, 0, 0,
                Booking.CONFIRMED, null, rid, "FULL_DAY"));

        assertThatThrownBy(() -> kernel.createBooking(new CreateBookingCmd(r.getId(), "同一请求", null,
                start, end, 0, 0, Booking.CONFIRMED, null, rid, "HOURLY")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("钟点与全日房的起步覆盖完全不同，回放旧单会让前端报错信息与实际账单对不上")
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    // ------------------------- ⑥⑦ 改变实际占用范围的动作也要跑冲突检测（第三轮审阅第 1 条）
    @Test
    @DisplayName("⑥ 提前入住也要跑冲突检测：不能把占用前扩进别人已订的时段")
    void earlyCheckInCannotOverlapExistingGuest() {
        Room r = room(2, 0, 0);
        // A 10:00–12:00、B 14:00–18:00，两单本来互不相干
        book(r.getId(), TODAY.withHour(10), TODAY.withHour(12), "HOURLY", null);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        assertThat(occupancyRows(r.getId())).isEqualTo(2);

        // 给 B 办 10:00 提前入住 = 把 B 的占用前扩到 A 头上。修复前这里直接改写 block_start，
        // 两个原本不冲突的预订就被一次入住操作叠到了一起
        assertThatThrownBy(() -> kernel.checkIn(b.id(), TODAY.withHour(10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);

        assertThat(blockStartOf(b.id())).as("被拒之后占用起点必须没被改写").isEqualTo(TODAY.withHour(14));
        assertThat(kernel.require(b.id()).getStatus()).as("入住状态随事务一起回滚").isEqualTo(Booking.CONFIRMED);
        assertThat(overlappingRows(r.getId())).isZero();
    }

    @Test
    @DisplayName("⑥ 合法的提前入住仍然要真的把前段占住（不能把检测做成一律拒绝）")
    void legitimateEarlyCheckInStillExtends() {
        Room r = room(2, 0, 0);
        // A 占到 10:00 整 —— 半开区间，B 从 10:00 起入住不算撞上 A
        book(r.getId(), TODAY.withHour(8), TODAY.withHour(10), "HOURLY", null);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);

        kernel.checkIn(b.id(), TODAY.withHour(10));   // 10:00 起没人订，扩到这里合法

        assertThat(blockStartOf(b.id())).isEqualTo(TODAY.withHour(10));
        assertThat(overlappingRows(r.getId())).as("边界相接不是重叠").isZero();
    }

    @Test
    @DisplayName("⑦ 提前入住之后再加时，扩出来的起点不能被抹回计划时间")
    void extendKeepsTheExtendedStart() {
        Room r = room(2, 0, 0);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(10));
        assertThat(blockStartOf(b.id())).isEqualTo(TODAY.withHour(10));

        bookingService.extend(b.id(), new ExtendReq(TODAY.withHour(20)));

        // 修复前 extend 用 booking.startTime 当起点，于是 10:00–14:00 那段"实际已住人"被抹平，
        // 又能被别人开走
        assertThat(blockStartOf(b.id())).as("加时重写整行时起点要沿用当前 block_start")
                .isEqualTo(TODAY.withHour(10));
        assertThat(overlappingRows(r.getId())).isZero();
    }

    // ------- ⑨ 入住本身也要验"这间房现在能不能交出去"（第四轮审阅第 1 条）

    @Test
    @DisplayName("⑨ 按时入住也拦得住：上一位客人超时未退房，就不能把房交给下一位")
    void onTimeCheckInIsBlockedByOverstayingGuest() {
        Room r = room(2, 0, 0);
        // 顺序很重要：B 必须在 A 变成"超时在住"之前就订好，
        // 否则连创建 B 都会被开单闸门挡住，就测不到"入住这一支"了
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        BookingVO a = book(r.getId(), TODAY.withHour(10), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(a.id(), TODAY.withHour(10));   // A 住进去、且此刻已过 12:00 未退

        assertThatThrownBy(() -> kernel.checkIn(b.id(), TODAY.withHour(14)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("开新单时的闸门拦不住「已存在的订单去兑付」")
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);
        assertThat(kernel.require(b.id()).getStatus()).as("入住状态随事务回滚").isEqualTo(Booking.CONFIRMED);
        assertThat(overlappingRows(r.getId())).isZero();
    }

    @Test
    @DisplayName("⑨ 房间还在等清扫时不能办入住；点完清扫就可以")
    void onTimeCheckInIsBlockedByPendingCleaning() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        BookingVO a = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(a.id(), TODAY.withHour(9));
        kernel.checkOut(a.id(), TODAY.withHour(12), 30);          // 留下 CLEANING 行

        assertThatThrownBy(() -> kernel.checkIn(b.id(), TODAY.withHour(14)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("没打扫的房不能进人 —— 这正是「保洁点了才算能卖」的含义")
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);

        bookingService.finishCleaning(r.getId());
        assertThat(kernel.checkIn(b.id(), TODAY.withHour(14)).getStatus()).isEqualTo(Booking.IN_USE);
    }

    @Test
    @DisplayName("⑨ 房间在办入住期间被改成维修，也不能办")
    void checkInIsBlockedWhenRoomIsNoLongerOnSale() {
        Room r = room(2, 0, 0);
        BookingVO b = book(r.getId(), TODAY.withHour(14), TODAY.withHour(18), "HOURLY", null);
        jdbc.update("update room set status = 'MAINTENANCE' where id = ?", r.getId());

        assertThatThrownBy(() -> kernel.checkIn(b.id(), TODAY.withHour(14)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);
    }

    @Test
    @DisplayName("⑩ 整段预订都已经过去，就不能再办入住 —— 先改期或重开单")
    void checkInIsRefusedWhenTheBookingWindowAlreadyEnded() {
        Room r = room(2, 0, 0);
        // B 订 10:00–12:00 一直没来；同一房间 A 订 13:00–16:00 并已入住
        BookingVO b = book(r.getId(), TODAY.withHour(10), TODAY.withHour(12), "HOURLY", null);
        BookingVO a = book(r.getId(), TODAY.withHour(13), TODAY.withHour(16), "HOURLY", null);
        kernel.checkIn(a.id(), TODAY.withHour(13));

        // 客人 14:00 才到。修复前只按 B 的历史窗口 [10:00, 12:30) 检测，查不到 A，于是"入住成功"，
        // 两个人就同住一间房 —— 闸门位置对了，但用的窗口是历史值
        assertThatThrownBy(() -> kernel.checkIn(b.id(), TODAY.withHour(14)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("这张单已经结束了，不该再被兑付")
                .isEqualTo(ErrorCode.BOOKING_ILLEGAL_STATE);
        assertThat(kernel.require(b.id()).getStatus()).isEqualTo(Booking.CONFIRMED);
    }

    @Test
    @DisplayName("⑪ 恰好卡在结束时刻也算过期（半开区间）")
    void checkInAtTheExactEndTimeIsRefused() {
        Room r = room(2, 0, 0);
        BookingVO b = book(r.getId(), TODAY.withHour(10), TODAY.withHour(12), "HOURLY", null);

        assertThatThrownBy(() -> kernel.checkIn(b.id(), TODAY.withHour(12)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.BOOKING_ILLEGAL_STATE);
    }

    @Test
    @DisplayName("⑫ 加锁后再 selectById 会命中一级缓存；锁查询本身必须返回最新行")
    void selectForUpdateReadsThroughTheFirstLevelCache() {
        Room r = room(2, 0, 0);
        transactionTemplate.executeWithoutResult(status -> {
            // 模拟 BookingService.ctx()：进内核之前先读一次房间
            Room beforeLock = roomMapper.selectById(r.getId());
            assertThat(beforeLock.getStatus()).isEqualTo(Room.ON_SALE);

            // 模拟"另一个事务把房间改成维修并提交"
            jdbc.update("update room set status = 'MAINTENANCE' where id = ?", r.getId());

            // 同一条语句 + 同样的参数 → MyBatis 一级缓存直接返回加锁前的对象
            assertThat(roomMapper.selectById(r.getId()).getStatus())
                    .as("这就是那个坑：看起来重新查了，其实读的是缓存")
                    .isEqualTo(Room.ON_SALE);

            // 锁查询是另一条语句，必然走库 → 拿到最新行
            assertThat(roomMapper.selectForUpdate(r.getId()).getStatus())
                    .as("所以 checkIn 只认锁查询的返回值，不再补一次 selectById")
                    .isEqualTo("MAINTENANCE");
        });
    }

    @Test
    @DisplayName("⑬ 晚退房把清扫挤进下一单时段时，清扫窗口要截断，不能造出重叠行")
    void lateCheckOutClipsTheCleaningWindow() {
        Room r = room(1, 0, 30);
        // 顺序：B 先订好，再让 A 入住 —— 否则 A 一变成"超时在住"，连创建 B 都会被开单闸门挡住
        BookingVO b = book(r.getId(), TODAY.withHour(12).plusMinutes(30), TODAY.withHour(14), "HOURLY", null);
        BookingVO a = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(a.id(), TODAY.withHour(9));

        // 12:00 的单拖到 12:15 才退；清扫 30 分钟会压到 12:30 开始的下一单头上
        kernel.checkOut(a.id(), TODAY.withHour(12).plusMinutes(15), 30);

        LocalDateTime cleanEnd = jdbc.queryForObject(
                "select block_end from occupancy where booking_id = ? and kind = 'CLEANING'",
                LocalDateTime.class, a.id());
        assertThat(cleanEnd).as("清扫窗口截断在下一段占用开始之前")
                .isEqualTo(TODAY.withHour(12).withMinute(30));
        assertThat(overlappingRows(r.getId())).as("行不重叠是全项目的前提").isZero();
    }

    @Test
    @DisplayName("⑭ 下一单已经开始时，清扫窗口被挤没：不造重叠行，也不假装可售")
    void cleaningWindowCanBeSqueezedToNothing() {
        // 房型退房缓冲设 0，A 的占用才止于 12:00，下一单才能紧跟着 12:00 开（两行不重叠）
        Room r = room(2, 0, 0);
        book(r.getId(), TODAY.withHour(12), TODAY.withHour(14), "HOURLY", null);
        BookingVO a = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(a.id(), TODAY.withHour(9));

        kernel.checkOut(a.id(), TODAY.withHour(12).plusMinutes(10), 30);   // 下一单 12:00 就开始了

        assertThat(rowsOfKind(r.getId(), "CLEANING")).as("挤没了就不该插行").isZero();
        assertThat(overlappingRows(r.getId())).isZero();
    }

    @Test
    @DisplayName("⑮ 内核下单时用锁内的最新房间状态复核 —— 锁外判过一次不算数")
    void createRechecksRoomStatusUnderLock() {
        Room r = room(2, 0, 0);
        jdbc.update("update room set status = 'MAINTENANCE' where id = ?", r.getId());

        assertThatThrownBy(() -> kernel.createBooking(new CreateBookingCmd(r.getId(), "客人", null,
                TODAY.withHour(10), TODAY.withHour(12), 0, 0, Booking.CONFIRMED, null, null, "HOURLY")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ON_SALE);
    }

    @Test
    @DisplayName("⑯ 提前入住有上限：超过 24 小时视为误操作")
    void earlyCheckInHasAnUpperBound() {
        Room r = room(2, 0, 0);
        BookingVO far = book(r.getId(), TODAY.plusDays(3).withHour(10), TODAY.plusDays(3).withHour(12),
                "HOURLY", null);

        assertThatThrownBy(() -> kernel.checkIn(far.id(), TODAY.withHour(10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("提前三天入住会把房间从现在起一路占住，几乎必然是误操作")
                .isEqualTo(ErrorCode.BOOKING_ILLEGAL_STATE);

        // 提前一小时是正常场景，必须放行
        BookingVO near = book(r.getId(), TODAY.plusDays(1).withHour(10), TODAY.plusDays(1).withHour(12),
                "HOURLY", null);
        assertThat(kernel.checkIn(near.id(), TODAY.plusDays(1).withHour(9)).getStatus())
                .isEqualTo(Booking.IN_USE);
    }

    // -------------------------- ⑧ 待清扫是确认式闸门，不是固定时段（第三轮审阅第 2 条）
    @Test
    @DisplayName("⑧ 没点「清扫完成」就一直挡房，预计时长过去也不例外；可售查询同口径")
    void unconfirmedCleaningKeepsBlockingPastItsEstimate() {
        Room r = room(2, 0, 30);
        BookingVO b = book(r.getId(), TODAY.withHour(9), TODAY.withHour(12), "HOURLY", null);
        kernel.checkIn(b.id(), TODAY.withHour(9));
        kernel.checkOut(b.id(), TODAY.withHour(12), 30);      // CLEANING 行 [12:00, 12:30]

        // 14:00 早已越过预计终点，但没人点确认 —— 需求方要的语义是"保洁点了才算能卖"
        assertThatThrownBy(() -> book(r.getId(), TODAY.withHour(14), TODAY.withHour(16), "HOURLY", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("修复前这里是「预计 45 分钟一过就自动放行」，与文档里的确认式闸门不一致")
                .isEqualTo(ErrorCode.ROOM_TIME_CONFLICT);

        assertThat(bookingService.availableRooms(null, TODAY.withHour(14), TODAY.withHour(16), null,
                com.school.bnb.booking.SaleType.HOURLY))
                .as("可售查询必须和内核同口径，否则前端点了才被拒")
                .extracting("id").doesNotContain(r.getId());

        assertThat(bookingService.finishCleaning(r.getId())).isEqualTo(1);
        assertThat(book(r.getId(), TODAY.withHour(14), TODAY.withHour(16), "HOURLY", null)).isNotNull();
    }

    // ---------------------------------------------------------------- 夹具

    private LocalDateTime blockStartOf(Long bookingId) {
        return jdbc.queryForObject(
                "select block_start from occupancy where booking_id = ? and kind = 'ROOM'",
                LocalDateTime.class, bookingId);
    }

    /**
     * 同一间房里两两重叠的占用行数 —— <b>任何时刻都必须是 0</b>。
     *
     * <p>整项目的（冲突检测、释放、报表口径）都是从这条不变量推出来的，
     * 所以每个会改写占用的动作都要顺手验它一次，而不是只验"这次操作本身对不对"。
     */
    private int overlappingRows(Long roomId) {
        Integer n = jdbc.queryForObject("""
                select count(1) from occupancy a
                join occupancy b on a.room_id = b.room_id and a.id < b.id
                where a.room_id = ?
                  and a.block_start < b.block_end
                  and a.block_end > b.block_start
                """, Integer.class, roomId);
        return n == null ? 0 : n;
    }
}
