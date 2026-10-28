package com.school.bnb.vo;

import java.time.Duration;
import java.time.LocalDateTime;

/** 一段空闲区间（已扣掉别人的占用与清扫缓冲）。 */
public record FreeSlotVO(LocalDateTime start, LocalDateTime end, long minutes) {

    public static FreeSlotVO of(LocalDateTime start, LocalDateTime end) {
        return new FreeSlotVO(start, end, Duration.between(start, end).toMinutes());
    }
}
