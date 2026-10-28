package com.school.bnb.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;

/** 房型新增/修改。id 为 null 表示新增。 */
public record RoomTypeReq(
        Long id,
        @NotNull(message = "必须归属一个民宿") Long propertyId,
        @NotBlank(message = "不能为空") String name,
        @NotNull @DecimalMin(value = "0.00", message = "不能为负") BigDecimal basePrice,
        @NotNull @Min(value = 1, message = "至少 1 小时") Integer minHours,
        @NotNull @DecimalMin(value = "0.00", message = "不能为负") BigDecimal extraHourPrice,
        @NotNull @Min(0) Integer cleanBeforeMinutes,
        @NotNull @Min(0) Integer cleanAfterMinutes,
        @NotNull LocalTime fullDayStart,
        @NotNull LocalTime fullDayEnd) {
}
