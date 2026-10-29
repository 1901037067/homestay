package com.school.bnb.service;

import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.entity.Property;
import com.school.bnb.mapper.StatsMapper;
import com.school.bnb.stats.StatsAggregator;
import com.school.bnb.vo.StatRow;
import com.school.bnb.vo.StatsVO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 报表服务：取数 → 纯函数聚合 → 组装。**这一层没有业务判断**，判断全在聚合器里，
 * 所以"这个数为什么是这样"永远只需要看一个地方。
 *
 * <p>默认区间取最近 14 个营业日：演示数据就是按"最近一周"铺的（实施计划第 6 节），
 * 打开就有形状，不用先点日期。
 */
@Service
public class StatsService {

    private static final int DEFAULT_DAYS = 14;

    private final StatsMapper statsMapper;
    private final PropertyService propertyService;

    public StatsService(StatsMapper statsMapper, PropertyService propertyService) {
        this.statsMapper = statsMapper;
        this.propertyService = propertyService;
    }

    public StatsVO report(Long propertyId, LocalDate from, LocalDate to) {
        Property p = propertyService.requireVisible(propertyId);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        // 先校验再取数：区间颠倒或超长时，不该先扫一遍库再让聚合器抛异常
        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "结束营业日不能早于开始营业日");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 366) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报表区间最长一年");
        }

        int sellable = statsMapper.countSellableRooms(propertyId);
        // 宽松超集（前一天的 00:00 ～ 后两天的 00:00），精确归属交给聚合器 ——
        // 一个边界只准有一个权威，见 StatsMapper 类注释
        List<StatRow> rows = statsMapper.scanBookings(propertyId,
                start.minusDays(1).atStartOfDay(), end.plusDays(2).atStartOfDay());

        StatsAggregator.Result r = StatsAggregator.aggregate(
                new StatsAggregator.Input(start, end, p.getDayCutTime(), sellable, rows));

        int bookings = r.daily().stream().mapToInt(StatsAggregator.Daily::bookings).sum();
        int overstay = r.daily().stream().mapToInt(StatsAggregator.Daily::overstay).sum();
        int earlyLeave = r.daily().stream().mapToInt(StatsAggregator.Daily::earlyLeave).sum();

        return new StatsVO(p.getId(), p.getName(), p.getDayCutTime(), start, end, sellable,
                new StatsVO.Totals(r.totalRevenue(), r.totalPendingBookings(), r.totalSoldRoomDays(),
                        r.avgOccupancy(), bookings, overstay, earlyLeave),
                r.daily(), r.heat(), r.ranking());
    }

    /**
     * 导出 CSV（带 UTF-8 BOM，Excel 双击直接正常显示中文）。
     *
     * <p><b>为什么不是 .xlsx</b>：为一张能一次装下的表引入 Apache POI 要背十几兆依赖和几十个类，
     * 而这一列数据的价值在"能贴进论文/发给店长看"，不在格式本身。CSV + BOM 在这个体量下
     * 是更诚实的选择；真要 xlsx 再换，导出逻辑被收敛在 {@link #toCsv} 一个方法里。
     */
    public byte[] exportCsv(StatsVO vo) {
        return toCsv(vo).getBytes(StandardCharsets.UTF_8);
    }

    static String toCsv(StatsVO vo) {
        StringBuilder sb = new StringBuilder("\uFEFF");   // BOM：少了它 Excel 会按 GBK 读，中文全乱
        sb.append("营业日,可售房间,售出房间,出租率%,已结算营收,待结算单数,订单数,超时未退房,提前退房\n");
        for (StatsAggregator.Daily d : vo.daily()) {
            sb.append(d.businessDay()).append(',')
                    .append(d.sellableRooms()).append(',')
                    .append(d.soldRooms()).append(',')
                    .append(d.occupancyRate().toPlainString()).append(',')
                    .append(d.revenue().toPlainString()).append(',')
                    .append(d.pendingBookings()).append(',')
                    .append(d.bookings()).append(',')
                    .append(d.overstay()).append(',')
                    .append(d.earlyLeave()).append('\n');
        }
        StatsVO.Totals t = vo.totals();
        sb.append("合计,").append(vo.sellableRooms()).append(',')
                .append(t.soldRoomDays()).append(',')
                .append(String.format(java.util.Locale.ROOT, "%.2f", t.avgOccupancy())).append(',')
                .append(t.revenue().toPlainString()).append(',')
                .append(t.pendingBookings()).append(',')
                .append(t.bookings()).append(',')
                .append(t.overstay()).append(',')
                .append(t.earlyLeave()).append('\n');
        // 说明写进文件本身：脱离界面的表格最容易把"营业日"读成"自然日"
        sb.append("# 营业日按各店 day_cut_time（").append(vo.dayCutTime())
                .append("）切割，凌晨的单算前一天；营收只计已结算订单\n");
        return sb.toString();
    }
}
