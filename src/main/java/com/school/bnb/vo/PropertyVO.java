package com.school.bnb.vo;

import com.school.bnb.entity.Property;
import java.time.LocalTime;

public record PropertyVO(
        Long id,
        String name,
        String address,
        LocalTime dayCutTime,
        LocalTime openTime,
        LocalTime closeTime,
        Long roomTypeCount,
        Long roomCount) {

    public static PropertyVO of(Property p, long roomTypeCount, long roomCount) {
        return new PropertyVO(p.getId(), p.getName(), p.getAddress(), p.getDayCutTime(), p.getOpenTime(),
                p.getCloseTime(), roomTypeCount, roomCount);
    }
}
