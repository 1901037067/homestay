package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.booking.BookingKernel;
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
import com.school.bnb.mapper.OccupancyMapper;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.vo.BookingVO;
import com.school.bnb.vo.FreeSlotVO;
import com.school.bnb.vo.RoomVO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预订域的对外服务：把"房源上下文"翻译成内核需要的区间与缓冲，再做业务校验。
 *
 * <p>分工要说清楚：内核只认区间 + 缓冲分钟数（为了能用纯单测覆盖 5.1 的边界），
 * 而<b>清扫缓冲必须由服务端从房型解析</b> —— 让前端传缓冲，等于允许它绕过缓冲下单。
 *
 * <p>校验分两层：业务规则（房间是否在售、起步时长、可售窗口）可以在进锁之前先拒，
 * 便宜且失败得快；<b>冲突检测必须在锁内</b>，一步都不能提前。
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
        String status = req.status() == null || req.status().isBlank() ? Booking.CONFIRMED : req.status();
        if (!CREATABLE.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "下单状态只能是 HELD 或 CONFIRMED，收到 " + status);
        }
        assertSellable(ctx, req.startTime(), req.endTime());

        LocalDateTime holdExpire = Booking.HELD.equals(status)
                ? LocalDateTime.now().plusMinutes(req.holdMinutes() == null ? DEFAULT_HOLD_MINUTES : req.holdMinutes())
                : null;

        Booking b = kernel.createBooking(new CreateBookingCmd(
                ctx.room().getId(), req.guestName().trim(), req.guestPhone(),
                req.startTime(), req.endTime(),
                ctx.type().getCleanBeforeMinutes(), ctx.type().getCleanAfterMinutes(),
                status, holdExpire));
        return toVO(b, ctx);
    }

    @Transactional
    public BookingVO changeTimes(Long bookingId, ChangeTimesReq req) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        assertSellable(ctx, req.startTime(), req.endTime());
        return toVO(kernel.changeTimes(bookingId, req.startTime(), req.endTime(),
                ctx.type().getCleanBeforeMinutes(), ctx.type().getCleanAfterMinutes()), ctx);
    }

    @Transactional
    public BookingVO extend(Long bookingId, ExtendReq req) {
        Booking b = kernel.require(bookingId);
        Ctx ctx = ctx(b.getRoomId());
        // 加时不能把结束时刻推出可售窗口（同一天内的延长时间才算钟点加时）
        boolean sameDay = b.getStartTime().toLocalDate().equals(req.newEndTime().toLocalDate());
        if (sameDay && req.newEndTime().toLocalTime().isAfter(ctx.property().getCloseTime())) {
            throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                    "加时后的结束时间 " + req.newEndTime().toLocalTime() + " 超出可售窗口 "
                            + ctx.property().getCloseTime());
        }
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
     * 某房在 [start, end) 内的空闲区间 = 窗口减去所有占用块（缓冲已经含在 block 里，不再重复扣）。
     *
     * <p>这里<b>只做几何相减，不套可售窗口</b>：可售性是"这一段能不能卖"的业务判断，
     * 由 create 在锁外先拒、内核在锁内兜住；房态日历要画的是"这段时间是不是空的"，
     * 两者混在一个接口里会让日历出现说不清楚的洞。
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
                // 与当前游标不相干（已被覆盖或在游标之前），只把游标往后推
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

    public List<RoomVO> availableRooms(Long propertyId, LocalDateTime start, LocalDateTime end, Integer minHours) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "end 必须晚于 start");
        }
        List<Room> rooms = roomMapper.findAvailable(propertyId, start, end);
        if (rooms.isEmpty()) {
            return List.of();
        }
        Map<Long, RoomType> types = roomTypeMapper.selectList(null).stream()
                .collect(Collectors.toMap(RoomType::getId, t -> t));
        Map<Long, String> propNames = propertyMapper.selectList(null).stream()
                .collect(Collectors.toMap(Property::getId, Property::getName));
        long minMinutes = minHours == null ? 0 : Math.max(minHours, 0) * 60L;
        boolean sameDay = start.toLocalDate().equals(end.toLocalDate());
        long requested = Duration.between(start, end).toMinutes();

        List<RoomVO> out = new ArrayList<>();
        for (Room r : rooms) {
            RoomType t = types.get(r.getRoomTypeId());
            if (t == null) {
                continue;
            }
            // 起步时长只约束同一天内的请求；全日房必然跨天，自动豁免（见 assertSellable 的注释）
            if (sameDay && requested < Math.max(minMinutes, t.getMinHours() * 60L)) {
                continue;
            }
            out.add(RoomVO.of(r, propNames.get(r.getPropertyId()), t.getName()));
        }
        return out;
    }

    // ------------------------------------------------------------ 规则

    /**
     * 可售窗口与起步时长的落地规则（实施计划 2.5）：
     *
     * <ul>
     *   <li><b>入住时刻必须落在 [open_time, close_time]</b> —— 全日房模板 14:00 入住，
     *       天然满足，所以不需要给它写一条"豁免"分支；</li>
     *   <li><b>起步时长只约束"同一天内结束"的请求</b> —— 全日房必然跨天，因此自动不受
     *       {@code min_hours} 限制。</li>
     * </ul>
     * 这两条比"按房型是不是全日房"来判断更稳：房型表里全日房和钟点房共用同一套时段字段。
     */
    private void assertSellable(Ctx ctx, LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            throw new BizException(ErrorCode.INVALID_TIME_RANGE, "结束时间必须晚于开始时间");
        }
        if (!Room.ON_SALE.equals(ctx.room().getStatus())) {
            throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                    "房间 " + ctx.room().getRoomNo() + " 当前状态 " + ctx.room().getStatus() + "，不可售");
        }
        RoomType type = ctx.type();
        Property property = ctx.property();
        LocalTime open = property.getOpenTime();
        LocalTime close = property.getCloseTime();

        if (start.toLocalTime().isBefore(open) || start.toLocalTime().isAfter(close)) {
            throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                    "入住时刻 " + start.toLocalTime() + " 不在可售窗口 " + open + "–" + close + " 内");
        }
        boolean sameDay = start.toLocalDate().equals(end.toLocalDate());
        if (sameDay) {
            if (end.toLocalTime().isAfter(close)) {
                throw new BizException(ErrorCode.ROOM_NOT_ON_SALE,
                        "钟点房结束时刻 " + end.toLocalTime() + " 超出可售窗口 " + close);
            }
            long minutes = Duration.between(start, end).toMinutes();
            if (minutes < type.getMinHours() * 60L) {
                throw new BizException(ErrorCode.INVALID_TIME_RANGE,
                        "该房型起步 " + type.getMinHours() + " 小时，当前只订了 " + minutes + " 分钟");
            }
        }
    }

    // ------------------------------------------------------------ 上下文与装配

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
