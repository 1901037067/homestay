package com.school.bnb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    public RoomService(RoomMapper roomMapper, RoomTypeMapper roomTypeMapper,
                       BookingMapper bookingMapper, PropertyService propertyService,
                       OperationLogService opLog) {
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.bookingMapper = bookingMapper;
        this.propertyService = propertyService;
        this.opLog = opLog;
    }

    public Page<RoomVO> page(long pageNo, long pageSize, Long propertyId, Long roomTypeId,
                             String status, String keyword) {
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
        String status = req.status() == null || req.status().isBlank() ? Room.ON_SALE : req.status();
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
            require(req.id());
            roomMapper.updateById(r);
        }
        return r;
    }

    /** 只改状态 —— 下架是删除的替代动作（实施计划 2.6）。 */
    @Transactional
    public void changeStatus(Long id, String status) {
        if (!STATUSES.contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态不合法: " + status);
        }
        Room r = require(id);
        String from = r.getStatus();
        r.setStatus(status);
        roomMapper.updateById(r);
        opLog.record("ROOM_STATUS", "room", id, r.getRoomNo() + " 状态 " + from + " → " + status, from, status);
    }

    /** 房间一旦有过订单就不能物理删除，否则历史账单指向幽灵房间、报表口径全乱。 */
    @Transactional
    public void delete(Long id) {
        Room r = require(id);
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
