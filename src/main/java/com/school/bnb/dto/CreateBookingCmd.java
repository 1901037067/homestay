package com.school.bnb.dto;

import java.time.LocalDateTime;

/**
 * 下单命令 —— 内核的输入。
 *
 * <p>{@code cleanBeforeMinutes/cleanAfterMinutes} 由调用方从 room_type 读出后传入，
 * 内核不再查表，这样 5.1 的边界用例可以用纯单测覆盖。
 *
 * <p>内核只认"区间 + 缓冲 + 幂等键"，<b>不做可售性策略判断</b>（那是 SellPolicy 的事），
 * 也不做状态机判断（那是条件更新的 where 子句的事）。
 */
public record CreateBookingCmd(
        Long roomId,
        String guestName,
        String guestPhone,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int cleanBeforeMinutes,
        int cleanAfterMinutes,
        String status,
        LocalDateTime holdExpireAt,
        String requestId,
        String saleType) {

    /** 内部调用与测试用的简化构造：不幂等、按钟点。对外 HTTP 一律走全参构造。 */
    public CreateBookingCmd(Long roomId, String guestName, String guestPhone,
                            LocalDateTime startTime, LocalDateTime endTime,
                            int cleanBeforeMinutes, int cleanAfterMinutes,
                            String status, LocalDateTime holdExpireAt) {
        this(roomId, guestName, guestPhone, startTime, endTime, cleanBeforeMinutes, cleanAfterMinutes,
                status, holdExpireAt, null, com.school.bnb.booking.SaleType.HOURLY.name());
    }
}
