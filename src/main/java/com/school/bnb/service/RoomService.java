package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.school.bnb.common.AccessScope;
import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.dto.RoomReq;
import com.school.bnb.entity.Booking;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.BookingMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import com.school.bnb.vo.RoomVO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private static final Set<String> STATUSES = Set.of(Room.ON_SALE, Room.MAINTENANCE, Room.OFFLINE);

    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final BookingMapper bookingMapper;
    private final PropertyService propertyService;
    private final OperationLogService opLog;
    private final AccessScope scope;

    public RoomService(RoomMapper roomMapper, RoomTypeMapper roomTypeMapper,
                       BookingMapper bookingMapper, PropertyService propertyService,
                       OperationLogService opLog,
                       AccessScope scope) {
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.bookingMapper = bookingMapper;
        this.propertyService = propertyService;
        this.opLog = opLog;
        this.scope = scope;
    }

    public Page<RoomVO> page(long pageNo, long pageSize, Long propertyId, Long roomTypeId,
                             String status, String keyword) {
        propertyId = scope.narrow(propertyId);
        LambdaQueryWrapper<Room> q = new LambdaQueryWrapper<Room>()
                .eq(propertyId != null, Room::getPropertyId, propertyId)
                .eq(roomTypeId != null, Room::getRoomTypeId, roomTypeId)
                .eq(status != null && !status.isBlank(), Room::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), Room::getRoomNo, keyword)
                .orderByAsc(Room::getPropertyId)
                .orderByAsc(Room::getRoomNo);

        Page<Room> raw = roomMapper.selectPage(new Page<>(pageNo, pageSize), q);

        Map<Long, String> typeNames = typeNameMap();
        Map<Long, String> propNames = propertyService.nameMap();
        List<RoomVO> rows = raw.getRecords().stream()
                .map(r -> RoomVO.of(r, propNames.get(r.getPropertyId()), typeNames.get(r.getRoomTypeId())))
                .toList();

        Page<RoomVO> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(rows);
        return out;
    }

    public Room require(Long id) {
        Room r = roomMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + id);
        }
        return r;
    }

    @Transactional
    public Room save(RoomReq req) {
        propertyService.require(req.propertyId());
        RoomType type = roomTypeMapper.selectById(req.roomTypeId());
        if (type == null) {
            throw new BizException(ErrorCode.ROOM_TYPE_NOT_FOUND, "房型不存在: " + req.roomTypeId());
        }
        if (!type.getPropertyId().equals(req.propertyId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "房型不属于所选民宿");
        }
        scope.requireVisible(req.propertyId());
        // 更新时没给状态就沿用库里的值：否则只改房号的请求会把"维修中"悄悄改成"在售"
        Room before = req.id() == null ? null : require(req.id());
        String status = req.status() == null || req.status().isBlank()
                ? (before == null ? Room.ON_SALE : before.getStatus()) : req.status();
        if (!STATUSES.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态不合法: " + status);
        }

        Room r = new Room();
        r.setId(req.id());
        r.setPropertyId(req.propertyId());
        r.setRoomTypeId(req.roomTypeId());
        r.setRoomNo(req.roomNo().trim());
        r.setFloorNo(req.floorNo());
        r.setStatus(status);
        if (req.id() == null) {
            roomMapper.insert(r);
        } else {
            Room existing = before;
            // 有订单的房间不许跨店改挂：报表与营收是按"房间当前所属民宿"归集的，
            // 改挂会把历史营收一起搬到另一家店 —— 对账时就成了凭空多出来的收入。
            // 与房型的处理保持一致（那条规则在 RoomTypeService 里）
            if (!existing.getPropertyId().equals(req.propertyId()) && bookingCount(req.id()) > 0) {
                throw new BizException(ErrorCode.HAS_CHILDREN,
                        "该房间已有 " + bookingCount(req.id()) + " 条订单，不能改挂到其它民宿；"
                                + "需要换店请新建房间，旧房间下架保留历史");
            }
            roomMapper.updateById(r);
        }
        return r;
    }

    private long bookingCount(Long roomId) {
        Long n = bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>().eq(Booking::getRoomId, roomId));
        return n == null ? 0 : n;
    }

    /** 只改状态 —— 下架是删除的替代动作（实施计划 2.6）。 */
    @Transactional
    public void changeStatus(Long id, String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态不合法: " + status);
        }
        // 先锁房间再改：不锁的话"下架"与"开单"可以交错 —— 单子按加锁前的 ON_SALE 过了校验，
        // 房间随后被下架，于是这张单永远办不了入住，房间还被"占"着
        Room r = roomMapper.selectForUpdate(id);
        if (r == null) {
            throw new BizException(ErrorCode.ROOM_NOT_FOUND, "房间不存在: " + id);
        }
        scope.requireVisible(r.getPropertyId());
        String from = r.getStatus();
        if (!from.equals(status) && (Room.OFFLINE.equals(status) || Room.MAINTENANCE.equals(status))) {
            long live = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                    .eq(Booking::getRoomId, id)
                    .in(Booking::getStatus, Booking.HELD, Booking.CONFIRMED, Booking.IN_USE));
            if (live > 0) {
                // 下架/维修是"这间房暂时不能卖"，但已经卖出去的单子必须有着落：
                // 先取消或改期，再下架。否则会留下一堆永远办不了入住的 CONFIRMED 单
                throw new BizException(ErrorCode.HAS_CHILDREN,
                        "该房间还有 " + live + " 条未完成订单（在住/已确认/预占），"
                                + "请先取消或改期，再改为 " + status);
            }
        }
        r.setStatus(status);
        roomMapper.updateById(r);
        opLog.record("ROOM_STATUS", "room", id, r.getPropertyId(), r.getRoomNo() + " 状态 " + from + " → " + status, from, status);
    }

    /** 房间一旦有过订单就不能物理删除，否则历史账单指向幽灵房间、报表口径全乱。 */
    @Transactional
    public void delete(Long id) {
        Room r = require(id);
        scope.requireVisible(r.getPropertyId());
        Long bookings = bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>().eq(Booking::getRoomId, r.getId()));
        if (bookings != null && bookings > 0) {
            throw new BizException(ErrorCode.ROOM_HAS_HISTORY,
                    "该房间有 " + bookings + " 笔历史订单，禁止删除；请改为下架(OFFLINE)");
        }
        roomMapper.deleteById(id);
    }

    private Map<Long, String> typeNameMap() {
        return roomTypeMapper.selectList(null).stream()
                .collect(Collectors.toMap(RoomType::getId, RoomType::getName));
    }
}
