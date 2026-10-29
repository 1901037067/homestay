package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.booking.BookingKernel;
import com.school.bnb.billing.PricingFrames;
import com.school.bnb.booking.SaleType;
import com.school.bnb.booking.SellPolicy;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.ChangeTimesReq;
import com.school.bnb.dto.CreateBookingCmd;
import com.school.bnb.dto.CreateBookingReq;
import com.school.bnb.dto.ExtendReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Occupancy;
import com.school.bnb.entity.Property;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.mapper.OccupancyMapper;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.vo.BookingVO;
import com.school.bnb.vo.FreeSlotVO;
import com.school.bnb.vo.RoomVO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预订域对外服务：把房源上下文翻译成内核需要的区间与缓冲，可售性判断交给 {@link SellPolicy}。
 *
 * <p>分工：策略判断（能不能卖）在进锁之前先拒，便宜且失败得快；
 * 冲突判断（有没有被别人占了）留在内核、锁后当前读。<b>两件事不能互换位置</b> ——
 * 策略放锁内会拉长临界区，冲突放锁外就是超卖。
 *
 * <p>唯一的例外是<b>幂等回放</b>：它排在策略预判之前。重试要拿回的是"第一次已经成功"那张单，
 * 而不是"此刻还能不能卖"的答案（见 {@code BookingKernel#replayOf}）。
 */
@Service
public class BookingService {

    private static final Set<String> CREATABLE = Set.of(Booking.HELD, Booking.CONFIRMED);
    private static final int DEFAULT_HOLD_MINUTES = 15;

    private final BookingKernel kernel;
    private final BookingMapper bookingMapper;
    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final PropertyMapper propertyMapper;
    private final OccupancyMapper occupancyMapper;
    private final RoomContext roomContext;
    private final OperationLogService opLog;

    public BookingService(BookingKernel kernel, BookingMapper bookingMapper, RoomMapper roomMapper,
                          RoomTypeMapper roomTypeMapper, PropertyMapper propertyMapper,
                          OccupancyMapper occupancyMapper, RoomContext roomContext,
                          OperationLogService opLog) {
        this.kernel = kernel;
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.propertyMapper = propertyMapper;
        this.occupancyMapper = occupancyMapper;
        this.roomContext = roomContext;
        this.opLog = opLog;
    }

    // ------------------------------------------------------------ 写操作

    @Transactional
    public BookingVO create(CreateBookingReq req) {
        Ctx ctx = ctx(req.roomId());
        SaleType type = parseSaleType(req.saleType());
        String status = req.status() == null || req.status().isBlank() ? Booking.CONFIRMED : req.status();
        if (!CREATABLE.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "下单状态只能是 HELD 或 CONFIRMED，收到 " + status);
        }
        LocalDateTime holdExpire = Booking.HELD.equals(status)
                ? LocalDateTime.now().plusMinutes(req.holdMinutes() == null ? DEFAULT_HOLD_MINUTES : req.holdMinutes())
                : null;
        CreateBookingCmd cmd = new CreateBookingCmd(
                ctx.room().getId(), req.guestName().trim(), req.guestPhone(),
                req.startTime(), req.endTime(),
                ctx.type().getCleanBeforeMinutes(), ctx.type().getCleanAfterMinutes(),
                status, holdExpire, req.requestId(), type.name());

        // 回放探测必须在可售性预判<b>之前</b>：重试意味着第一次已经写进库了，
        // 而"第一次成功、响应超时、前台重试"这几分钟里房间很可能被改成维修、
        // 房型可能调过最短时长。先跑预判，重试就会收到 2003/3004 —— 前台以为没开出去，
        // 于是再开一张，同一间房被重复占用。已经在库里成功过的请求，答案只能是那张原单
        Booking replay = kernel.replayOf(cmd);
        if (replay != null) {
            return toVO(replay, ctx);
        }

        assertSellable(ctx, req.startTime(), req.endTime(), type);

        BookingKernel.CreateOutcome outcome = kernel.createOrReplay(cmd);
        Booking b = outcome.booking();
        // 幂等回放时**不能**再做这两件事：
        //   ① 写计费快照 —— 会用"现在的"房型配置覆盖掉下单时冻结的值，V7 白做；
        //   ② 记开单日志 —— 同一张单会出现两条 BOOKING_CREATE，审计上看像开了两次。
        // 典型触发：第一次提交成功但响应超时，前台重试同一个 requestId。
        if (outcome.created()) {
            // 计费基准在下单时就冻结：结算时再读 room_type 的话，"下单后改房型"会改掉一张旧单的金额
            PricingFrames.Frames frames = PricingFrames.of(type, ctx.type(), req.startTime());
            bookingMapper.snapshotPricing(b.getId(), frames.coveredMinutes(), frames.chargeUnitMinutes());
            b.setCoveredMinutes(frames.coveredMinutes());
            b.setChargeUnitMinutes(frames.chargeUnitMinutes());
            opLog.record("BOOKING_CREATE", "booking", b.getId(),
                    String.format("%s %s｜%s → %s（%s）", ctx.room().getRoomNo(), b.getGuestName(),
                            b.getStartTime(), b.getEndTime(), b.getStatus()),
                    null, b);
        }
        return toVO(b, ctx);
    }

    @Transactional
    public BookingVO changeTimes(Long bookingId, ChangeTimesReq req) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        assertSellable(ctx, req.startTime(), req.endTime(), parseSaleType(b.getSaleType()));
        Booking after = kernel.changeTimes(bookingId, req.startTime(), req.endTime(),
                ctx.type().getCleanBeforeMinutes(), ctx.type().getCleanAfterMinutes());
        opLog.record("BOOKING_RESCHEDULE", "booking", bookingId,
                String.format("%s %s｜改为 %s → %s", ctx.room().getRoomNo(), after.getBookingNo(),
                        after.getStartTime(), after.getEndTime()),
                snapshot(b), after);
        return toVO(after, ctx);
    }

    @Transactional
    public BookingVO extend(Long bookingId, ExtendReq req) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        // 加时走独立规则：全日房复用下单策略会被"区间必须等于模板"挡住，
        // 而加时本来就是要偏离模板
        SellPolicy.Verdict v = SellPolicy.checkExtension(
                rule(ctx.room(), ctx.type(), ctx.property()), b.getStartTime(), b.getEndTime(), req.newEndTime());
        if (!v.sellable()) {
            throw new BizException(v.code(), v.reason());
        }
        Booking after = kernel.extend(bookingId, req.newEndTime(), ctx.type().getCleanAfterMinutes());
        opLog.record("BOOKING_EXTEND", "booking", bookingId,
                String.format("%s %s｜离店从 %s 延到 %s", ctx.room().getRoomNo(), after.getBookingNo(),
                        b.getEndTime(), after.getEndTime()),
                snapshot(b), after);
        return toVO(after, ctx);
    }

    @Transactional
    public BookingVO confirm(Long bookingId) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        Booking after = kernel.confirm(bookingId);
        opLog.record("BOOKING_CONFIRM", "booking", bookingId,
                ctx.room().getRoomNo() + " " + after.getBookingNo() + " 预占转确认", snapshot(b), after);
        return toVO(after, ctx);
    }

    @Transactional
    public BookingVO checkIn(Long bookingId) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        Booking after = kernel.checkIn(bookingId, LocalDateTime.now());
        opLog.record("BOOKING_CHECK_IN", "booking", bookingId,
                String.format("%s %s｜%s 入住（计划 %s）", ctx.room().getRoomNo(), after.getBookingNo(),
                        after.getGuestName(), after.getStartTime()),
                snapshot(b), after);
        return toVO(after, ctx);
    }

    @Transactional
    public BookingVO checkOut(Long bookingId) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        Booking before = snapshot(b);
        int configured = ctx.type().getCleanAfterMinutes();
        // 秒对齐：datetime 列只存到秒，带着纳秒传进去，Java 里算出的窗口长度就和库里存的对不上
        // （实测差 0.4 秒跨过分单位，日志写 9 分钟、行里量出 10 分钟）。既然要拿它算分钟写日志，就取整到秒
        BookingKernel.CheckOutOutcome out = kernel.checkOut(bookingId, LocalDateTime.now().withNano(0), configured);
        Booking after = out.booking();
        opLog.record("BOOKING_CHECK_OUT", "booking", bookingId,
                String.format("%s 退房（%s），%s", ctx.room().getRoomNo(), after.getBookingNo(),
                        cleaningNote(configured, out)),
                before, after);
        return toVO(after, ctx);
    }

    /**
     * 退房日志里清扫那一句的措辞。<b>以实际写下的清扫窗口为准，不照抄房型配置</b>：
     * 配置 30 分钟、但下一单已经从 12:10 开始，那实际只挡住了 10 分钟；挤到零时窗口长度为 0
     * 却仍然留下一行待确认的清扫（它是一件待办，不是一个时段）。日志写错，审计就成了误导。
     */
    static String cleaningNote(int configuredMinutes, BookingKernel.CheckOutOutcome out) {
        if (!out.needsCleaning()) {
            return "无需清扫，直接可售";
        }
        long actual = out.actualMinutes();
        if (actual == configuredMinutes) {
            return "转清扫 " + actual + " 分钟";
        }
        if (actual <= 0) {
            return "清扫窗口被下一单挤成 0 分钟，仍需点「清扫完成」才放行";
        }
        return "转清扫 " + actual + " 分钟（配置 " + configuredMinutes + "，被下一单截断）";
    }

    /**
     * 清扫完成。只删占用行、不改订单状态 —— 订单已经是 CHECKED_OUT 了。
     * 所以这里不套状态机，但照样要拿房间行锁。
     */
    @Transactional
    public int finishCleaning(Long roomId) {
        Ctx ctx = ctx(roomId);
        int n = kernel.finishCleaning(roomId);
        if (n > 0) {
            opLog.record("CLEANING_DONE", "room", roomId,
                    ctx.room().getRoomNo() + " 清扫完成，恢复可售", null, null);
        }
        return n;
    }

    @Transactional
    public BookingVO cancel(Long bookingId) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        Booking after = kernel.cancel(bookingId);
        opLog.record("BOOKING_CANCEL", "booking", bookingId,
                String.format("%s %s｜取消 %s → %s 的订单（%s）", ctx.room().getRoomNo(),
                        after.getBookingNo(), after.getStartTime(), after.getEndTime(), after.getGuestName()),
                snapshot(b), after);
        return toVO(after, ctx);
    }

    // ------------------------------------------------------------ 读操作

    public Page<BookingVO> list(long pageNo, long pageSize, String status, Long roomId,
                                String keyword, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<Booking> q = new LambdaQueryWrapper<Booking>()
                .eq(status != null && !status.isBlank(), Booking::getStatus, status)
                .eq(roomId != null, Booking::getRoomId, roomId)
                .ge(from != null, Booking::getStartTime, from)
                .lt(to != null, Booking::getStartTime, to)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Booking::getGuestName, keyword).or()
                        .like(Booking::getBookingNo, keyword))
                .orderByDesc(Booking::getId);

        Page<Booking> raw = bookingMapper.selectPage(new Page<>(pageNo, pageSize), q);
        Page<BookingVO> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(toVOs(raw.getRecords()));
        return out;
    }

    public BookingVO get(Long bookingId) {
        return toVOs(List.of(kernel.require(bookingId))).get(0);
    }

    /**
     * 某房在 [start, end) 内的空闲区间 = 窗口减去所有占用块（缓冲已含在 block 里，不重复扣）。
     *
     * <p><b>只做几何相减，不套可售策略。</b>可售性是"这段能不能卖"，由 {@link SellPolicy} 在
     * create 与 availableRooms 里判；日历要画的是"这段时间是不是空的"。
     * 把两者混进一个接口，日历会出现"没人住却画成不可点"的说不清的洞。
     */
    public List<FreeSlotVO> freeSlots(Long roomId, LocalDateTime start, LocalDateTime end, Integer minHours) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "end 必须晚于 start");
        }
        ctx(roomId);

        long minMinutes = minHours == null ? 0 : Math.max(minHours, 0) * 60L;
        List<Occupancy> blocks = occupancyMapper.findBlocksIn(roomId, start, end);

        List<FreeSlotVO> free = new ArrayList<>();
        LocalDateTime cursor = start;
        for (Occupancy o : blocks) {
            if (!o.getBlockStart().isAfter(cursor)) {
                if (o.getBlockEnd().isAfter(cursor)) {
                    cursor = o.getBlockEnd();
                }
                continue;
            }
            free.add(FreeSlotVO.of(cursor, o.getBlockStart()));
            if (o.getBlockEnd().isAfter(cursor)) {
                cursor = o.getBlockEnd();
            }
        }
        if (cursor.isBefore(end)) {
            free.add(FreeSlotVO.of(cursor, end));
        }
        return free.stream().filter(s -> s.minutes() >= minMinutes).toList();
    }

    /** 可售房间查询 —— 和 create 走同一个 {@link SellPolicy}，不会再出现"查询说能卖、下单被拒"。 */
    public List<RoomVO> availableRooms(Long propertyId, LocalDateTime start, LocalDateTime end,
                                       Integer minHours, SaleType saleType) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "end 必须晚于 start");
        }
        SaleType type = saleType == null ? SaleType.HOURLY : saleType;
        List<Room> rooms = roomMapper.findAvailable(propertyId, start, end);
        if (rooms.isEmpty()) {
            return List.of();
        }
        Map<Long, RoomType> types = roomTypeMapper.selectList(null).stream()
                .collect(Collectors.toMap(RoomType::getId, t -> t));
        Map<Long, Property> properties = propertyMapper.selectList(null).stream()
                .collect(Collectors.toMap(Property::getId, p -> p));

        List<RoomVO> out = new ArrayList<>();
        for (Room r : rooms) {
            RoomType t = types.get(r.getRoomTypeId());
            Property p = properties.get(r.getPropertyId());
            if (t == null || p == null) {
                continue;
            }
            if (!SellPolicy.isSellable(rule(r, t, p), start, end, type)) {
                continue;
            }
            // minHours 参数只在钟点语义下有意义，且不能比房型的起步时长更宽松
            if (type == SaleType.HOURLY && minHours != null
                    && Duration.between(start, end).toMinutes() < Math.max(minHours, t.getMinHours()) * 60L) {
                continue;
            }
            out.add(RoomVO.of(r, p.getName(), t.getName()));
        }
        return out;
    }

    // ------------------------------------------------------------ 规则与装配

    private static SellPolicy.Rule rule(Room room, RoomType type, Property property) {
        return new SellPolicy.Rule(room.getStatus(), room.getRoomNo(), property.getOpenTime(),
                property.getCloseTime(), type.getMinHours(), type.getFullDayStart(), type.getFullDayEnd());
    }

    private void assertSellable(Ctx ctx, LocalDateTime start, LocalDateTime end, SaleType type) {
        SellPolicy.Verdict v = SellPolicy.check(rule(ctx.room(), ctx.type(), ctx.property()), start, end, type);
        if (!v.sellable()) {
            throw new BizException(v.code(), v.reason());
        }
    }

    private static SaleType parseSaleType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SaleType.HOURLY;
        }
        try {
            return SaleType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "saleType 只能是 HOURLY 或 FULL_DAY，收到 " + raw);
        }
    }

    private record Ctx(Room room, RoomType type, Property property) {
    }

    /** 取房间上下文这件事本身交给 {@link RoomContext}，计费走的是同一条链，不允许两处各判各的空值。 */
    private Ctx ctx(Long roomId) {
        RoomContext.Data d = roomContext.of(roomId);
        return new Ctx(d.room(), d.type(), d.property());
    }

    private BookingVO toVO(Booking b, Ctx ctx) {
        return BookingVO.of(b, ctx.room().getRoomNo(), ctx.room().getPropertyId(),
                ctx.property().getName(), ctx.type().getName());
    }

    /**
     * 日志要的是"改之前长什么样"。必须拷一份 —— {@code kernel} 里是就地改同一个实体对象，
     * 直接把引用当 before 存进去，前后值会变成一模一样，审计等于白记。
     */
    private static Booking snapshot(Booking src) {
        Booking c = new Booking();
        c.setId(src.getId());
        c.setBookingNo(src.getBookingNo());
        c.setStatus(src.getStatus());
        c.setStartTime(src.getStartTime());
        c.setEndTime(src.getEndTime());
        c.setActualCheckIn(src.getActualCheckIn());
        c.setActualCheckOut(src.getActualCheckOut());
        c.setHoldExpireAt(src.getHoldExpireAt());
        c.setAmount(src.getAmount());
        return c;
    }

    /** 列表页整批装配：房间/房型/民宿各查一次，不逐行查（一页 20 条会放大成 60 次查询）。 */
    private List<BookingVO> toVOs(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        Set<Long> roomIds = bookings.stream().map(Booking::getRoomId).collect(Collectors.toSet());
        Map<Long, Room> rooms = roomMapper.selectBatchIds(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, r -> r));
        Set<Long> typeIds = rooms.values().stream().map(Room::getRoomTypeId).collect(Collectors.toSet());
        Set<Long> propIds = rooms.values().stream().map(Room::getPropertyId).collect(Collectors.toSet());
        Map<Long, RoomType> types = typeIds.isEmpty() ? Map.of()
                : roomTypeMapper.selectBatchIds(typeIds).stream()
                        .collect(Collectors.toMap(RoomType::getId, t -> t));
        Map<Long, Property> properties = propIds.isEmpty() ? Map.of()
                : propertyMapper.selectBatchIds(propIds).stream()
                        .collect(Collectors.toMap(Property::getId, p -> p));

        List<BookingVO> out = new ArrayList<>(bookings.size());
        for (Booking b : bookings) {
            Room room = rooms.get(b.getRoomId());
            RoomType type = room == null ? null : types.get(room.getRoomTypeId());
            Property property = room == null ? null : properties.get(room.getPropertyId());
            out.add(BookingVO.of(b,
                    room == null ? null : room.getRoomNo(),
                    room == null ? null : room.getPropertyId(),
                    property == null ? null : property.getName(),
                    type == null ? null : type.getName()));
        }
        return out;
    }
}
