package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 下单请求。清扫缓冲不由前端传 —— 服务端从房间所属房型解析，避免客户端能绕过缓冲下单。
 * status 只接受 HELD / CONFIRMED，其余一律拒。
 */
public record CreateBookingReq(
        @NotNull(message = "必须选择房间") Long roomId,
        @NotBlank(message = "不能为空") String guestName,
        String guestPhone,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime,
        String status,
        Integer holdMinutes) {
}
