package com.school.bnb.stats;

import com.school.bnb.vo.StatRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 报表聚合 —— <b>纯函数</b>：不碰数据库、不读时钟，给同样的输入必然给同样的数。
 *
 * <p>和 {@code PriceCalculator} 一个套路，理由也一样：营业日切割、跨零点摊小时、营收归属、
 * 超时判定这四件事全是"边界问题"，写在 SQL 里每个分支都得连库造数据才能验；
 * 写成纯函数，它们被一组表驱动用例钉住（{@code StatsAggregatorTest}），毫秒级跑完。
 *
 * <h3>四条口径（答辩会逐条问）</h3>
 * <ol>
 *   <li><b>营业日归属</b>：{@code bd(t) = date(t - dayCut)}，06:00 之前算前一天。
 *       归的是<b>入住时刻</b>所在营业日，不是离店时刻 —— 一张 14:00→次日 12:00 的全日单
 *       整段属于入住那天，不会被劈成两天。</li>
 *   <li><b>出租率 = 当天卖出去的不同房间数 / 可售房间数</b>。刻意不用"按小时加权占用率"：
 *       钟点房卖 4 小时和三小时房的"率"没法放同一个分母里解释，而"今天有几间房被卖出去过"
 *       是前台和店长都立刻听得懂的话。</li>
 *   <li><b>营收只认已结算</b>（{@code SETTLED}）。退房未结算的钱单列 {@code pendingAmount}，
 *       不能混进营收 —— 混进去就成了"嘴上收了"。这条和 W6 的账单快照是同一个原则。</li>
 *   <li><b>热度按小时切片，跨零点自然落到两个营业日</b>。横轴起点是切割点（06,07,…,05），
 *       所以"凌晨 2 点"落在第 20 格而不是第 2 格 —— 这正是实施计划 7.2 要求旋转的原因。</li>
 * </ol>
 */
public final class StatsAggregator {

    /** 取消与超时释放：没住过，不产生房晚也不产生营收。 */
    private static final Set<String> VOID = Set.of("CANCELLED", "RELEASED");
    private static final String SETTLED = "SETTLED";
    private static final String CHECKED_OUT = "CHECKED_OUT";

    private StatsAggregator() {
    }

    /**
     * @param from          起始营业日（含）
     * @param to            结束营业日（含）
     * @param dayCut        营业日切割点，来自 property.day_cut_time
     * @param sellableRooms 分母：该店在售房间数。房间会中途增减，这里按"当前"统计并在文档里写明
     */
    public record Input(LocalDate from, LocalDate to, LocalTime dayCut, int sellableRooms,
                        List<StatRow> rows) {
    }

    /**
     * 一个营业日的汇总。
     *
     * @param soldRooms   当天卖出去的不同房间数（同一间房当天卖两次只算一间）
     * @param occupancyRate soldRooms / sellableRooms，保留两位小数
     * @param revenue     已结算金额
     * @param pendingBookings 已退房未结算的<b>单数</b>（不是金额，见下面为什么）
     */
    public record Daily(LocalDate businessDay, int sellableRooms, int soldRooms,
                        BigDecimal occupancyRate, BigDecimal revenue, int pendingBookings,
                        int bookings, int overstay, int earlyLeave) {
    }

    /** 热力图的一格。slot 是旋转后的横轴下标（0 = 切割点所在小时），hour 是还原出来的钟点。 */
    public record Heat(LocalDate businessDay, int slot, int hour, int occupiedRooms) {
    }

    public record Rank(Long roomId, String roomNo, int soldTimes, BigDecimal revenue) {
    }

    public record Result(List<Daily> daily, List<Heat> heat, List<Rank> ranking,
                         BigDecimal totalRevenue, int totalPendingBookings,
                         int totalSoldRoomDays, double avgOccupancy) {
    }

    public static Result aggregate(Input in) {
        validate(in);
        int cutHour = in.dayCut().getHour();
        int cutMinute = in.dayCut().getMinute();

        Map<LocalDate, DailyAcc> acc = new TreeMap<>();
        for (LocalDate d = in.from(); !d.isAfter(in.to()); d = d.plusDays(1)) {
            acc.put(d, new DailyAcc(d));
        }

        Map<String, Set<Long>> heatRooms = new HashMap<>();
        Map<Long, RankAcc> rank = new LinkedHashMap<>();

        for (StatRow r : in.rows()) {
            if (r.getStatus() == null || VOID.contains(r.getStatus())) {
                continue;
            }
            // 热度先算：它按"住宿区间与查询窗口是否相交"统计，与房晚/营收的归属口径**不同**。
            // 一笔昨天 14:00 → 今天 12:00 的单，房晚算昨天，但它确实占着今天 06:00–12:00 那六格。
            // 第一版把"入住营业日不在区间内"的整笔订单直接跳过了，于是"只查今天"时
            // 热力图里这六格凭空消失 —— 热力图随查询范围变形，这在论文里是硬伤。
            spreadHours(r, in, heatRooms);

            LocalDate bd = businessDay(r.getStartTime(), cutHour, cutMinute);
            DailyAcc d = acc.get(bd);
            // 房晚、营收、单数、超时/早退、排行都只认"入住营业日"落在区间内的单。
            // 注意这里是 continue：不能只跳过日序列那一部分，否则区间外的单会混进排行，
            // 指标卡与排行互相打脸
            if (d == null) {
                continue;
            }
            d.soldRooms.add(r.getRoomId());
            d.bookings++;
            if (SETTLED.equals(r.getStatus())) {
                d.revenue = d.revenue.add(money(r.getAmount()));
            } else if (CHECKED_OUT.equals(r.getStatus())) {
                // 只数单数，不累金额：正常流程里 amount 在结算那一刻才写回，
                // 退房后一直是 0。之前按"累加 amount"统计，于是真实的待结算永远是 0 元，
                // 只有测试里手写金额的那行才显得对 —— 那是测试在骗自己
                d.pendingBookings++;
            }
            if (r.getActualCheckOut() != null) {
                if (r.getActualCheckOut().isAfter(r.getEndTime())) {
                    d.overstay++;
                } else if (r.getActualCheckOut().isBefore(r.getEndTime())) {
                    d.earlyLeave++;
                }
            }

            RankAcc ra = rank.computeIfAbsent(r.getRoomId(), id -> new RankAcc(r.getRoomNo()));
            ra.soldTimes++;
            if (SETTLED.equals(r.getStatus())) {
                ra.revenue = ra.revenue.add(money(r.getAmount()));
            }
        }

        List<Daily> daily = new ArrayList<>(acc.size());
        int totalSold = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        int totalPendingBookings = 0;
        for (DailyAcc a : acc.values()) {
            daily.add(a.toDaily(in.sellableRooms()));
            totalSold += a.soldRooms.size();
            totalRevenue = totalRevenue.add(a.revenue);
            totalPendingBookings += a.pendingBookings;
        }
        int days = Math.max(1, acc.size());
        double avg = in.sellableRooms() <= 0 ? 0
                : round2(totalSold * 100.0 / ((double) in.sellableRooms() * days));

        return new Result(daily, toHeat(heatRooms, cutHour, in.from(), in.to()), toRanking(rank),
                money(totalRevenue), totalPendingBookings, totalSold, avg);
    }

    /** 营业日归属：往前推切割点后取日期。这是全报表唯一的归一化入口。 */
    public static LocalDate businessDay(LocalDateTime t, int cutHour, int cutMinute) {
        return t.minusHours(cutHour).minusMinutes(cutMinute).toLocalDate();
    }

    /** 旋转后的横轴下标：0 = 切割点那一小时。dayCut 06:00 时，02:00 落在第 20 格。 */
    public static int slotOf(LocalDateTime t, int cutHour) {
        return ((t.getHour() - cutHour) % 24 + 24) % 24;
    }

    // ------------------------------------------------------------------ 内部

    /** 把一单按小时切片撒进热力格；跨零点会自然落进两个营业日。 */
    private static void spreadHours(StatRow r, Input in, Map<String, Set<Long>> heat) {
        int cutHour = in.dayCut().getHour();
        int cutMinute = in.dayCut().getMinute();
        LocalDateTime t = r.getStartTime().truncatedTo(ChronoUnit.HOURS);
        int guard = 0;
        while (t.isBefore(r.getEndTime()) && guard++ < 24 * 60) {
            LocalDate bd = businessDay(t, cutHour, cutMinute);
            // 跨出区间的尾巴不画：这张图只表示区间内的营业日
            if (!bd.isBefore(in.from()) && !bd.isAfter(in.to())) {
                heat.computeIfAbsent(bd + "#" + slotOf(t, cutHour), k -> new HashSet<>())
                        .add(r.getRoomId());
            }
            t = t.plusHours(1);
        }
    }

    private static List<Heat> toHeat(Map<String, Set<Long>> heat, int cutHour,
                                     LocalDate from, LocalDate to) {
        return heat.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("#");
                    int slot = Integer.parseInt(parts[1]);
                    return new Heat(LocalDate.parse(parts[0]), slot,
                            (cutHour + slot) % 24, e.getValue().size());
                })
                .filter(h -> !h.businessDay().isBefore(from) && !h.businessDay().isAfter(to))
                .sorted(Comparator.comparing(Heat::businessDay).thenComparingInt(Heat::slot))
                .toList();
    }

    private static List<Rank> toRanking(Map<Long, RankAcc> rank) {
        return rank.entrySet().stream()
                .map(e -> new Rank(e.getKey(), e.getValue().roomNo, e.getValue().soldTimes,
                        money(e.getValue().revenue)))
                .sorted(Comparator.comparing(Rank::revenue).reversed()
                        .thenComparing(Comparator.comparingInt(Rank::soldTimes).reversed())
                        .thenComparing(Rank::roomNo))
                .toList();
    }

    private static void validate(Input in) {
        if (in.from() == null || in.to() == null) {
            throw new IllegalArgumentException("报表区间不能为空");
        }
        if (in.to().isBefore(in.from())) {
            throw new IllegalArgumentException("结束营业日不能早于开始营业日");
        }
        if (in.dayCut() == null) {
            throw new IllegalArgumentException("营业日切割点不能为空");
        }
        // 超过一年基本是误操作（点了两次日历），挡住它免得把整表扫进内存
        if (ChronoUnit.DAYS.between(in.from(), in.to()) > 366) {
            throw new IllegalArgumentException("报表区间最长一年");
        }
    }

    /**
     * 金额统一两位小数。DB 里 amount 是 decimal(10,2)，读出来本来就有两位；
     * 但累加器的初值是 scale 0 的 {@code ZERO}，不归一化的话"一分钱没收"那天会输出 {@code 0}
     * 而不是 {@code 0.00} —— JSON 和 CSV 里两种写法混排，看着就像哪一处算错了。
     */
    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static final class DailyAcc {
        final LocalDate day;
        final Set<Long> soldRooms = new HashSet<>();
        BigDecimal revenue = BigDecimal.ZERO;
        int pendingBookings;
        int bookings;
        int overstay;
        int earlyLeave;

        DailyAcc(LocalDate day) {
            this.day = day;
        }

        Daily toDaily(int sellableRooms) {
            BigDecimal rate = sellableRooms <= 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(soldRooms.size() * 100.0 / sellableRooms)
                            .setScale(2, RoundingMode.HALF_UP);
            return new Daily(day, sellableRooms, soldRooms.size(), rate,
                    money(revenue), pendingBookings, bookings, overstay, earlyLeave);
        }
    }

    private static final class RankAcc {
        final String roomNo;
        int soldTimes;
        BigDecimal revenue = BigDecimal.ZERO;

        RankAcc(String roomNo) {
            this.roomNo = roomNo;
        }
    }
}
