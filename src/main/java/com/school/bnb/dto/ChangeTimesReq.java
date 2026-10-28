package com.school.bnb.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 改期。售卖方式不在请求里 —— 沿用订单原类型，防止前端借改期把全日房改成钟点。 */
public record ChangeTimesReq(@NotNull LocalDateTime startTime, @NotNull LocalDateTime endTime) {
}
