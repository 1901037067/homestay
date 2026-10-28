package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.booking.BookingKernel;
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

    public BookingService(BookingKernel kernel, BookingMapper bookingMapper, RoomMapper roomMapper,
                          RoomTypeMapper roomTypeMapper, PropertyMapper propertyMapper,
                          OccupancyMapper occupancyMapper) {
        this.kernel = kernel;
        this.bookingMapper = bookingMapper;
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.propertyMapper = propertyMapper;
        this.occupancyMapper = occupancyMapper;
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
        assertSellable(ctx, req.startTime(), req.endTime(), type);

        LocalDateTime holdExpire = Booking.HELD.equals(status)
                ? LocalDateTime.now().plusMinutes(req.holdMinutes() == null ? DEFAULT_HOLD_MINUTES : req.holdMinutes())
                : null;

        Booking b = kernel.createBooking(new CreateBookingCmd(
                ctx.room().getId(), req.guestName().trim(), req.guestPhone(),
                req.startTime(), req.endTime(),
                ctx.type().getCleanBeforeMinutes(), ctx.type().getCleanAfterMinutes(),
                status, holdExpire, req.requestId(), type.name()));
        return toVO(b, ctx);
    }

    @Transactional
    public BookingVO changeTimes(Long bookingId, ChangeTimesReq req) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        // 沿用订单原本的售卖方式，不接受请求里带类型
        assertSellable(ctx, req.startTime(), req.endTime(), parseSaleType(b.getSaleType()));
        return toVO(kernel.changeTimes(bookingId, req.startTime(), req.endTime(),
                ctx.type().getCleanBeforeMinutes(), ctx.type().getCleanAfterMinutes()), ctx);
    }

    @Transactional
    public BookingVO extend(Long bookingId, ExtendReq req) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        assertSellable(ctx, b.getStartTime(), req.newEndTime(), parseSaleType(b.getSaleType()));
        return toVO(kernel.extend(bookingId, req.newEndTime(), ctx.type().getCleanAfterMinutes()), ctx);
    }

    @Transactional
    public BookingVO confirm(Long bookingId) {
        Booking b = kernel.require(bookingId);
        return toVO(kernel.confirm(bookingId), ctx(b.getRoomId()));
    }

    @Transactional
    public BookingVO checkIn(Long bookingId) {
        Booking b = kernel.require(bookingId);
        return toVO(kernel.checkIn(bookingId, LocalDateTime.now()), ctx(b.getRoomId()));
    }

    @Transactional
    public BookingVO checkOut(Long bookingId) {
        Booking b = kernel.require(bookingId);
        return toVO(kernel.checkOut(bookingId, LocalDateTime.now()), ctx(b.getRoomId()));
    }

    @Transactional
    public BookingVO cancel(Long bookingId) {
        Booking b = kernel.require(bookingId);
        return toVO(kernel.cancel(bookingId), ctx(b.getRoomId()));
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

    private Ctx ctx(Long roomId) {
        Room room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + roomId);
        }
        RoomType type = roomTypeMapper.selectById(room.getRoomTypeId());
        if (type == null) {
            throw new BizException(ErrorCode.ROOM_TYPE_NOT_FOUND, "房间未配置房型: " + roomId);
        }
        Property property = propertyMapper.selectById(room.getPropertyId());
        if (property == null) {
            throw new BizException(ErrorCode.PROPERTY_NOT_FOUND, "房型未配置民宿: " + room.getPropertyId());
        }
        return new Ctx(room, type, property);
    }

    private BookingVO toVO(Booking b, Ctx ctx) {
        return BookingVO.of(b, ctx.room().getRoomNo(), ctx.room().getPropertyId(),
                ctx.property().getName(), ctx.type().getName());
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
