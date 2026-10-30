package com.school.bnb.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 计费纯函数 —— <b>不碰数据库、不读时钟、不看 session</b>，给同样的输入必然给同样的钱。
 *
 * <p>这是全项目最适合写单测的一块：输入是一张参数表，输出是金额，
 * 没有容器、没有夹具，一个用例跑不到一毫秒。表驱动单测见 {@code PriceCalculatorTest}。
 *
 * <h3>计价模型（三段，按顺序）</h3>
 * <ol>
 *   <li><b>起步价</b>覆盖 {@code coveredMinutes} 分钟。钟点房传 {@code min_hours×60}，
 *       全日房传房型模板时长（14:00→次日 12:00 就是 1320）。订得比它短<b>不退差价</b>，
 *       这就是"起步时长"在钱上的含义。</li>
 *   <li><b>超时费</b>按 {@code chargeUnitMinutes} 粒度从 {@code start+covered} 起切单元，
 *       不足一个单元按一个单元收（行业惯例"不足一小时按一小时"）。</li>
 *   <li>每个单元的单价 = 平时超时单价，被该单元<b>起点时刻</b>命中的规则改写。</li>
 * </ol>
 *
 * <h3>三条必须说清的口径（答辩会问，写在这儿免得散落在注释里）</h3>
 * <ul>
 *   <li><b>规则不叠加</b>：一个时刻只取一条规则，决胜顺序 priority 升 → 房型级压民宿级 → id 升。
 *       做成可叠加就得定义叠加顺序，而乘法与一口价混着叠根本没有正确答案。</li>
 *   <li><b>规则声明自己改的是哪一笔钱</b>（{@link RateRuleDef.AppliesTo}）：房费是打包价、
 *       超时是元/小时，同一个数值套两种量纲会算出荒谬金额 ——
 *       "假日一口价 488"若连小时价也一起变成 488，延迟退房一小时就收 976。
 *       起步段只接受 ALL/BASE，超时段只接受 ALL/OVERAGE。</li>
 *   <li><b>每一段按自己那一段的起点定价</b>：连住 N 晚就切 N 个起步段，各自按那一晚的入住时刻
 *       选规则；超时费按各单元起点定价。所以跨了节假日边界的长住单，国庆内那几晚各收一口价、
 *       外面那几晚各收平时价 —— 而不是整单跟着入住那天走。</li>
 *   <li><b>取整只在单元级发生</b>（HALF_UP 到分），明细金额是它包含的单元金额之和，
 *       合计是明细金额之和。<b>合计永远等于打印出来的分项相加</b>，
 *       不会出现"明细加起来比合计差一分"这种对不上账的脏事。</li>
 * </ul>
 */
public final class PriceCalculator {

    /** 分档时刻只精确到分钟，标签里带上日期，跨零点长单的明细才读得懂。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 相邻两晚起点的间隔，与 {@code SellPolicy.fullDayNights} 用的是同一个几何。 */
    private static final int DAY_MINUTES = 24 * 60;

    private PriceCalculator() {
    }

    public record Input(LocalDateTime start,
                        LocalDateTime end,
                        int coveredMinutes,
                        int nights,
                        BigDecimal basePrice,
                        BigDecimal extraHourPrice,
                        int chargeUnitMinutes,
                        List<RateRuleDef> rules) {

        /**
         * 钟点房与"只住一晚"的全日房走这里 —— 起步段就是一段、一次打包价。
         *
         * <p>留着这个旧签名是为了不让 34 条表驱动用例为了一个新字段集体改行：
         * 它们验的是单晚口径，本来也不该被迫关心连住。
         */
        public Input(LocalDateTime start, LocalDateTime end, int coveredMinutes,
                     BigDecimal basePrice, BigDecimal extraHourPrice,
                     int chargeUnitMinutes, List<RateRuleDef> rules) {
            this(start, end, coveredMinutes, 1, basePrice, extraHourPrice, chargeUnitMinutes, rules);
        }
    }

    /**
     * 一条账单明细。
     *
     * @param kind        BASE（起步价）/ OVERAGE（超时费）
     * @param unitPrice   BASE 是这一晚/这一单的打包价；OVERAGE 是元/小时
     * @param unitHint    给前端显示单位用，避免把打包价误读成小时价（"打包" / "元/晚" / "元/小时"）
     * @param ruleName    命中的规则名；null = 没有规则命中，走平时价
     * @param amount      该条金额，等于它包含的各个计费单元金额之和
     */
    public record Line(String kind,
                       String label,
                       LocalDateTime from,
                       LocalDateTime to,
                       int chargeMinutes,
                       BigDecimal unitPrice,
                       String unitHint,
                       String ruleName,
                       BigDecimal amount) {
    }

    public record Quote(List<Line> lines,
                        BigDecimal baseAmount,
                        BigDecimal overageAmount,
                        BigDecimal total,
                        int overageUnits) {
    }

    public static Quote price(Input in) {
        validate(in);

        List<Line> lines = new ArrayList<>();
        BigDecimal baseAmount = in.nights() > 1 ? baseLinesPerNight(in, lines) : baseLineOnce(in, lines);

        BigDecimal overage = BigDecimal.ZERO;
        int units = 0;
        LocalDateTime tailStart = coveredEnd(in);
        if (in.end().isAfter(tailStart)) {
            List<Unit> tail = sliceTail(in, tailStart);
            units = tail.size();
            for (Line l : merge(tail)) {
                lines.add(l);
                overage = overage.add(l.amount());
            }
        }
        return new Quote(List.copyOf(lines), baseAmount, overage, baseAmount.add(overage), units);
    }

    /** 单晚（钟点房与只住一晚的全日房）：起步段就一条，金额是这一单的打包价。 */
    private static BigDecimal baseLineOnce(Input in, List<Line> lines) {
        RateRuleDef baseRule = selectBest(in.rules(), in.start(), RateRuleDef.AppliesTo.BASE);
        BigDecimal baseAmount = money(baseRule == null
                ? in.basePrice()
                : baseRule.applyTo(in.basePrice()));

        // 起步段的展示区间取 [start, min(覆盖终点, 实际终点))：订 2 小时却按 3 小时起步价收时，
        // 明细里画到第 3 小时会让人觉得多收的那一小时真实存在，而它只是"不退"。
        LocalDateTime baseTo = in.end().isBefore(coveredEnd(in)) ? in.end() : coveredEnd(in);
        lines.add(new Line("BASE",
                "起步价（含 " + minutes(in.coveredMinutes()) + "）",
                in.start(), baseTo, in.coveredMinutes(),
                baseAmount, "打包", baseRule == null ? null : baseRule.name(), baseAmount));
        return baseAmount;
    }

    /**
     * 连住 N 晚：<b>每晚各自按自己那一晚的起点选规则、各自定价</b>，出 N 条起步明细。
     *
     * <p>为什么不是"一口价 × N"：那等于让入住那一天的规则管住后面所有天 —— 9/28 入住订到 10/7，
     * 要么整单都按平时的 388、要么整单都按国庆的 488，两种都不对。
     * 而"起步价按入住时刻定价"本来就是"按那一段的起点定价"，连住只是把一段变成 N 段，没有破例。
     *
     * <p>每条写的是那一晚真实的 14:00 → 次日 12:00，所以 N 条的<b>分钟数之和小于</b>
     * {@code coveredMinutes} —— 差的是中间那些 12:00–14:00 的白天空档：客人房没退、含在房费里，
     * 但它不是一个独立计费单元，不该在明细里凭空长出来。<b>金额之和仍恒等于起步合计</b>，
     * 这条不变，也是 {@code 明细相加==合计} 那条断言守着的。
     */
    private static BigDecimal baseLinesPerNight(Input in, List<Line> lines) {
        int nightMinutes = nightMinutes(in);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < in.nights(); i++) {
            LocalDateTime from = in.start().plusMinutes(DAY_MINUTES * (long) i);
            LocalDateTime to = from.plusMinutes(nightMinutes);
            RateRuleDef rule = selectBest(in.rules(), from, RateRuleDef.AppliesTo.BASE);
            BigDecimal amount = money(rule == null ? in.basePrice() : rule.applyTo(in.basePrice()));
            total = total.add(amount);
            lines.add(new Line("BASE",
                    "起步价 · 第 " + (i + 1) + " 晚（" + from.format(TS) + " → " + to.format(TS) + "）",
                    from, to, nightMinutes, amount, "元/晚", rule == null ? null : rule.name(), amount));
        }
        return total;
    }

    /**
     * 反推一晚多少分钟。几何来自 {@code SellPolicy.fullDayNights}：
     * {@code coveredMinutes = 24h ×(N-1) + 一晚模板时长}，所以移一项就是模板时长。
     * 最后一晚的 {@code to} 因此正好落在 coveredEnd 上。
     */
    private static int nightMinutes(Input in) {
        return in.coveredMinutes() - DAY_MINUTES * (in.nights() - 1);
    }

    // ------------------------------------------------------------------ 切片

    /**
     * 超时部分切成计费单元。每个单元<b>独立取整</b>，最后再相加 ——
     * 若改成"总分钟数 × 单价 / 60 再取整"，同样一张单在粒度 60 和粒度 1 下会算出两个不同的钱，
     * 而这个差异没有任何业务含义。
     */
    private static List<Unit> sliceTail(Input in, LocalDateTime tailStart) {
        List<Unit> units = new ArrayList<>();
        LocalDateTime cursor = tailStart;
        while (cursor.isBefore(in.end())) {
            // 单元的 to 一律是"起点 + 整粒度"，越过 end 也不截短 ——
            // 最后一段不足一个单元仍按整单元收费，"不足一小时按一小时"就是在这一行发生的
            LocalDateTime next = cursor.plusMinutes(in.chargeUnitMinutes());
            RateRuleDef hit = selectBest(in.rules(), cursor, RateRuleDef.AppliesTo.OVERAGE);
            BigDecimal unitPrice = money(hit == null ? in.extraHourPrice() : hit.applyTo(in.extraHourPrice()));
            BigDecimal amount = money(unitPrice.multiply(BigDecimal.valueOf(in.chargeUnitMinutes()))
                    .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP));
            units.add(new Unit(cursor, next, in.chargeUnitMinutes(), unitPrice,
                    hit == null ? null : hit.name(), amount));
            cursor = next;
        }
        return units;
    }

    /** 连续同价、同规则的单元合成一条明细：粒度 60 的三小时超时该显示成一行，不该刷三屏。 */
    private static List<Line> merge(List<Unit> units) {
        List<List<Unit>> groups = new ArrayList<>();
        for (Unit u : units) {
            List<Unit> last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            // 只在"价钱与规则都一样"时并档。金额取各单元已取整金额之和，
            // 所以并档只是展示层的聚合，不会改变这张单收多少钱。
            if (last != null && samePrice(last.get(last.size() - 1), u)) {
                last.add(u);
            } else {
                groups.add(new ArrayList<>(List.of(u)));
            }
        }
        List<Line> out = new ArrayList<>(groups.size());
        for (List<Unit> g : groups) {
            Unit first = g.get(0);
            Unit lastU = g.get(g.size() - 1);
            int minutes = g.stream().mapToInt(Unit::minutes).sum();
            BigDecimal amount = g.stream().map(Unit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new Line("OVERAGE",
                    "超时费 " + first.from().format(TS) + "–" + lastU.to().format(TS),
                    first.from(), lastU.to(), minutes, first.unitPrice(), "元/小时",
                    first.ruleName(), amount));
        }
        return out;
    }

    private static boolean samePrice(Unit a, Unit b) {
        return a.unitPrice().compareTo(b.unitPrice()) == 0
                && java.util.Objects.equals(a.ruleName(), b.ruleName());
    }

    private record Unit(LocalDateTime from, LocalDateTime to, int minutes,
                        BigDecimal unitPrice, String ruleName, BigDecimal amount) {
    }

    // ------------------------------------------------------------------ 选规则

    /**
     * 命中的规则里挑一条。<b>决胜必须全序</b> —— 否则同一张单两次算出不同金额，
     * 快照就成了"看运气"，而且单测也写不出来。
     *
     * @param lineKind 这一行是起步段还是超时段；声明了只管另一头的规则在这里被筛掉
     */
    static RateRuleDef selectBest(List<RateRuleDef> rules, LocalDateTime at, RateRuleDef.AppliesTo lineKind) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        return rules.stream()
                .filter(r -> r.appliesToLine(lineKind))
                .filter(r -> r.matches(at))
                .min(Comparator.comparingInt(RateRuleDef::priority)
                        // 房型级更具体，同优先级时赢过民宿级
                        .thenComparing(r -> r.roomTypeScoped() ? 0 : 1)
                        .thenComparing(RateRuleDef::id))
                .orElse(null);
    }

    // ------------------------------------------------------------------ 内部

    private static LocalDateTime coveredEnd(Input in) {
        return in.start().plusMinutes(in.coveredMinutes());
    }

    private static void validate(Input in) {
        if (in.start() == null || in.end() == null) {
            throw new IllegalArgumentException("计费区间不能为空");
        }
        if (!in.end().isAfter(in.start())) {
            throw new IllegalArgumentException("结束必须晚于开始（半开区间 [start, end)）");
        }
        if (in.coveredMinutes() < 0) {
            throw new IllegalArgumentException("起步覆盖时长不能为负");
        }
        if (in.nights() < 1) {
            throw new IllegalArgumentException("连住晚数至少 1，收到 " + in.nights());
        }
        if (in.nights() > 1 && nightMinutes(in) < 1) {
            throw new IllegalArgumentException("起步覆盖时长与连住晚数不自洽：" + in.nights()
                    + " 晚要求 coveredMinutes 至少大于 " + DAY_MINUTES * (in.nights() - 1)
                    + " 分钟，收到 " + in.coveredMinutes());
        }
        if (in.chargeUnitMinutes() < 1) {
            throw new IllegalArgumentException("计费粒度至少 1 分钟，收到 " + in.chargeUnitMinutes());
        }
        if (in.basePrice() == null || in.extraHourPrice() == null) {
            throw new IllegalArgumentException("起步价与超时单价不能为空");
        }
    }

    /** 金额统一保留两位、四舍五入。分档以下的精度在本项目里没有意义。 */
    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** "1320" → "22 小时"，"150" → "2 小时 30 分钟"。账单给人看，别写分钟数让人心算。 */
    static String minutes(int m) {
        if (m < 60) {
            return m + " 分钟";
        }
        long h = Duration.ofMinutes(m).toHours();
        int rest = m - (int) h * 60;
        return rest == 0 ? h + " 小时" : h + " 小时 " + rest + " 分钟";
    }
}
