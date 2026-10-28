package com.school.bnb.vo;

import com.school.bnb.entity.Room;

/** 房间列表行：把房型名与民宿名带出来，前端不用为每一行再发一次请求。 */
public record RoomVO(
        Long id,
        Long propertyId,
        String propertyName,
        Long roomTypeId,
        String roomTypeName,
        String roomNo,
        Integer floorNo,
        String status) {

    public static RoomVO of(Room r, String propertyName, String roomTypeName) {
        return new RoomVO(r.getId(), r.getPropertyId(), propertyName, r.getRoomTypeId(), roomTypeName,
                r.getRoomNo(), r.getFloorNo(), r.getStatus());
    }
}
