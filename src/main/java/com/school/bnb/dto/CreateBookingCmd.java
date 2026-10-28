package com.school.bnb.dto;

import java.time.LocalDateTime;

/**
 * 下单命令。
 *
 * <p>{@code cleanBeforeMinutes/cleanAfterMinutes} 由调用方从 room_type 读出后传入，
 * 而不是在内核里再查一次表 —— 内核保持"给定区间和缓冲，只做锁与校验"这件事，
 * 单测就能覆盖全部边界（实施计划 5.1）。
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
        LocalDateTime holdExpireAt) {
}
