package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RoomReq(
        Long id,
        @NotNull(message = "必须归属一个民宿") Long propertyId,
        @NotNull(message = "必须选择一个房型") Long roomTypeId,
        @NotBlank(message = "不能为空") String roomNo,
        Integer floorNo,
        @Pattern(regexp = "ON_SALE|MAINTENANCE|OFFLINE", message = "状态不合法") String status) {
}
