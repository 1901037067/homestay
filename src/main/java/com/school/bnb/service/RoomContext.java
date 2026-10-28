package com.school.bnb.service;

import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.entity.Property;
import com.school.bnb.entity.Room;
import com.school.bnb.entity.RoomType;
import com.school.bnb.mapper.PropertyMapper;
import com.school.bnb.mapper.RoomMapper;
import com.school.bnb.mapper.RoomTypeMapper;
import org.springframework.stereotype.Component;

/**
 * "一间房 → 房型 → 民宿"这条上下文链的唯一取法。
 *
 * <p>抽出来的理由和 {@link com.school.bnb.booking.SellPolicy} 同源：下单、可售查询、房态、
 * 计费都要这三张表的同一份配置，各自写一遍就会各自漏判一种空值。
 * 缺失时的错误码在这里定死，调用方不必再判 null。
 */
@Component
public class RoomContext {

    public record Data(Room room, RoomType type, Property property) {
    }

    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;
    private final PropertyMapper propertyMapper;

    public RoomContext(RoomMapper roomMapper, RoomTypeMapper roomTypeMapper, PropertyMapper propertyMapper) {
        this.roomMapper = roomMapper;
        this.roomTypeMapper = roomTypeMapper;
        this.propertyMapper = propertyMapper;
    }

    public Data of(Long roomId) {
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
        return new Data(room, type, property);
    }
}
