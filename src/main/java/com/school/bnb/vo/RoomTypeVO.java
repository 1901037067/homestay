package com.school.bnb.vo;

import com.school.bnb.entity.RoomType;
import java.math.BigDecimal;
import java.time.LocalTime;

public record RoomTypeVO(
        Long id,
        Long propertyId,
        String propertyName,
        String name,
        BigDecimal basePrice,
        Integer minHours,
        BigDecimal extraHourPrice,
        Integer cleanBeforeMinutes,
        Integer cleanAfterMinutes,
        LocalTime fullDayStart,
        LocalTime fullDayEnd,
        Long roomCount) {

    public static RoomTypeVO of(RoomType t, String propertyName, long roomCount) {
        return new RoomTypeVO(t.getId(), t.getPropertyId(), propertyName, t.getName(), t.getBasePrice(),
                t.getMinHours(), t.getExtraHourPrice(), t.getCleanBeforeMinutes(), t.getCleanAfterMinutes(),
                t.getFullDayStart(), t.getFullDayEnd(), roomCount);
    }
}
