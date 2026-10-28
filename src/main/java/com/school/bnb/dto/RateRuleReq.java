package com.school.bnb.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 价格规则新增/修改。id 为 null 表示新增。
 *
 * <p>{@link #weekdays} 用 1..7 的整数列表（1=周一）而不是位掩码：
 * 前端七个复选框直接映射，后台表格里也能一眼读出"6,7 = 周末"。落库时拼成 CSV。
 */
public record RateRuleReq(
        Long id,
        @NotNull(message = "必须归属一个民宿") Long propertyId,
        /** null = 整家民宿通用。 */
        Long roomTypeId,
        @NotBlank(message = "不能为空") String name,
        LocalDate validFrom,
        LocalDate validTo,
        List<Integer> weekdays,
        LocalTime startHour,
        LocalTime endHour,
        @NotBlank(message = "必须选择计价方式") String mode,
        @NotNull @DecimalMin(value = "0.00", message = "不能为负") BigDecimal value,
        /** ALL（默认）/ BASE（只改房费）/ OVERAGE（只改超时单价）。 */
        String appliesTo,
        Integer priority,
        Boolean enabled) {
}
