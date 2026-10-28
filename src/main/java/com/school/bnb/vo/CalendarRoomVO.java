package com.school.bnb.vo;

import java.time.LocalTime;
import java.util.List;

/**
 * 日历的一行：一间房 + 它落在本营业日窗口内的所有占用块。
 *
 * <p>带上房型的整日房时段，是为了让"点空白格下单"能直接给出「全日房」这一档 ——
 * 否则前端要么写死 14:00/12:00（改配置就失效），要么再发一次房型请求。
 */
public record CalendarRoomVO(
        Long roomId,
        String roomNo,
        Long roomTypeId,
        String roomTypeName,
        String status,
        LocalTime fullDayStart,
        LocalTime fullDayEnd,
        List<CalendarBlock> blocks) {
}
