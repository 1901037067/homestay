package com.school.bnb.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 房态日历数据源。
 *
 * <p>窗口是 <b>[营业日 06:00, 次日 06:00)</b> 而不是自然日 —— 横轴从 dayCutTime 起转，
 * 凌晨那几单的归属才和报表口径一致（实施计划 7.2）。前端拿到 windowStart 之后
 * 只需要按分钟偏移定位，不必自己再算切割。
 */
public record CalendarVO(
        LocalDate businessDay,
        LocalTime dayCutTime,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Long propertyId,
        String propertyName,
        LocalTime openTime,
        LocalTime closeTime,
        List<CalendarRoomVO> rooms) {
}
