package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 下单请求。
 *
 * <p>清扫缓冲不由前端传 —— 服务端从房间所属房型解析，否则等于允许客户端绕过缓冲下单。
 * {@code saleType} 必须显式声明（HOURLY / FULL_DAY），不再由"是否跨天"推断。
 */
public record CreateBookingReq(
        @NotNull(message = "必须选择房间") Long roomId,
        @NotBlank(message = "不能为空") String guestName,
        String guestPhone,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime,
        String status,
        Integer holdMinutes,
        String saleType,
        /** 客户端生成的幂等键。双击或超时重试时带同一个值，服务端返回原单而不是再造一单。 */
        String requestId) {

    /** 测试与内部调用用：saleType 与 requestId 留空，由服务端按 HOURLY 与"不幂等"处理。 */
    public CreateBookingReq(Long roomId, String guestName, String guestPhone,
                            LocalDateTime startTime, LocalDateTime endTime,
                            String status, Integer holdMinutes) {
        this(roomId, guestName, guestPhone, startTime, endTime, status, holdMinutes, null, null);
    }
}
