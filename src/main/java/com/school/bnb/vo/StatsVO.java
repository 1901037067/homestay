package com.school.bnb.vo;

import com.school.bnb.stats.StatsAggregator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 报表返回体：<b>一次请求、一次扫描、一份自洽的快照</b>。
 *
 * <p>实施计划原本把概览/热度/排行写成三个接口，实现时合成一个：三个接口各自扫一遍库，
 * 期间若有新单进来，指标卡和图表就会互相矛盾 —— 答辩时被指着问"这两个数为什么对不上"很致命。
 * 合成一个接口之后，"同一份数据算出来的三个视图"是结构上的保证，不是靠自觉。
 *
 * <p>内部复用 {@link StatsAggregator} 的几个 record，不再抄一层映射：
 * 它们本来就是扁平的展示数据，多一层同名 VO 只会多一处会写错的地方。
 */
public record StatsVO(
        Long propertyId,
        String propertyName,
        LocalTime dayCutTime,
        LocalDate from,
        LocalDate to,
        int sellableRooms,
        Totals totals,
        List<StatsAggregator.Daily> daily,
        List<StatsAggregator.Heat> heat,
        List<StatsAggregator.Rank> ranking) {

    public record Totals(BigDecimal revenue, BigDecimal pendingAmount, int soldRoomDays,
                         double avgOccupancy, int bookings, int overstay, int earlyLeave) {
    }
}
