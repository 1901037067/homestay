package com.school.bnb.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.school.bnb.billing.RateRuleDef.AppliesTo;
import com.school.bnb.billing.RateRuleDef.Mode;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 计费纯函数的表驱动单测 —— 全项目<b>不需要容器</b>的一类测试。
 *
 * <p>每张表一行只写"输入 + 期望的三个金额"，跑一遍不到一毫秒。
 * 这张表本身可以直接抄进论文当"计费用例表"，而它之所以能这么写，
 * 全靠 {@link PriceCalculator} 不碰数据库、不读时钟。
 *
 * <p>基准日期都是 2026-10：10-03 周六、10-05 周一、10-09 周五、10-10 周六、10-11 周日。
 * {@link #fixtureDatesAreWhatTheyClaim()} 先把这个假设钉住 —— 日期是不是星期几是算出来的，
 * 不是我以为的；万一我推错了，应该让这条测试红，而不是让后面几十条静默测错东西。
 * （第一次写这张表时我就把 10-08 当成了周五，实际是周四，正是这条测试当场抓出来的。）
 */
class PriceCalculatorTest {

    private static final LocalDateTime MON_1400 = LocalDateTime.of(2026, 10, 5, 14, 0);
    private static final BigDecimal BASE_68 = new BigDecimal("68.00");
    private static final BigDecimal EXTRA_20 = new BigDecimal("20.00");

    // ------------------------------------------------------------------ 表驱动

    static Stream<Arguments> pricingTable() {
        List<RateRuleDef> weekend = List.of(
                onDays(1, "周末上浮", 100, false, Mode.MULTIPLY, "1.20", DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        List<RateRuleDef> nightWindow = List.of(
                inHours(1, "深夜加价", 100, false, Mode.MULTIPLY, "1.50", "22:00", "02:00"));
        List<RateRuleDef> nationalDay = List.of(
                onDates(1, "国庆一口价", 10, true, Mode.OVERRIDE, "488.00", "2026-10-01", "2026-10-07"));

        return Stream.of(
                // 名称, 起, 止, 起步覆盖分钟, 起步价, 超时单价, 粒度, 规则, 期望起步, 期望超时, 期望合计
                Arguments.of("订满起步时长", MON_1400, MON_1400.plusHours(3), 180,
                        BASE_68, EXTRA_20, 60, List.of(), "68.00", "0.00", "68.00"),
                Arguments.of("短于起步时长也不退差价", MON_1400, MON_1400.plusHours(1).plusMinutes(30), 180,
                        BASE_68, EXTRA_20, 60, List.of(), "68.00", "0.00", "68.00"),
                Arguments.of("超时整一小时", MON_1400, MON_1400.plusHours(4), 180,
                        BASE_68, EXTRA_20, 60, List.of(), "68.00", "20.00", "88.00"),
                Arguments.of("超时 61 分钟按两小时收（不足一小时按一小时）",
                        MON_1400, MON_1400.plusHours(4).plusMinutes(1), 180,
                        BASE_68, EXTRA_20, 60, List.of(), "68.00", "40.00", "108.00"),
                Arguments.of("同一张单改成半小时粒度只收 1.5 小时",
                        MON_1400, MON_1400.plusHours(4).plusMinutes(1), 180,
                        BASE_68, EXTRA_20, 30, List.of(), "68.00", "30.00", "98.00"),
                Arguments.of("按分钟收则 61 分钟就是 61 分钟",
                        MON_1400, MON_1400.plusHours(4).plusMinutes(1), 180,
                        BASE_68, EXTRA_20, 1, List.of(), "68.00", "20.13", "88.13"),
                Arguments.of("周末规则同时改写起步价与超时价",
                        LocalDateTime.of(2026, 10, 10, 14, 0), LocalDateTime.of(2026, 10, 10, 18, 0), 180,
                        BASE_68, EXTRA_20, 60, weekend, "81.60", "24.00", "105.60"),
                Arguments.of("跨星期边界：起步价按入住那天，超时段各自定价",
                        LocalDateTime.of(2026, 10, 9, 22, 0), LocalDateTime.of(2026, 10, 10, 1, 0), 60,
                        BASE_68, EXTRA_20, 60, weekend, "68.00", "44.00", "112.00"),
                Arguments.of("深夜窗口只对窗口内的单元加价",
                        MON_1400.plusHours(6), MON_1400.plusHours(9).plusMinutes(30), 60,
                        BASE_68, EXTRA_20, 60, nightWindow, "68.00", "80.00", "148.00"),
                Arguments.of("22:00 整退房不沾深夜加价（半开区间）",
                        MON_1400.plusHours(6), MON_1400.plusHours(8), 60,
                        BASE_68, EXTRA_20, 60, nightWindow, "68.00", "20.00", "88.00"),
                Arguments.of("全日房国庆一口价覆盖模板",
                        LocalDateTime.of(2026, 10, 3, 14, 0), LocalDateTime.of(2026, 10, 4, 12, 0), 1320,
                        new BigDecimal("388.00"), new BigDecimal("40.00"), 60, nationalDay, "488.00", "0.00", "488.00"),
                Arguments.of("国庆区间外仍是平时价",
                        LocalDateTime.of(2026, 10, 8, 14, 0), LocalDateTime.of(2026, 10, 9, 12, 0), 1320,
                        new BigDecimal("388.00"), new BigDecimal("40.00"), 60, nationalDay, "388.00", "0.00", "388.00"),
                Arguments.of("全日房加时两小时按超时单价收",
                        LocalDateTime.of(2026, 10, 5, 14, 0), LocalDateTime.of(2026, 10, 6, 14, 0), 1320,
                        new BigDecimal("388.00"), new BigDecimal("40.00"), 60, List.of(), "388.00", "80.00", "468.00"),
                Arguments.of("倍率产生小数时四舍五入到分",
                        MON_1400, MON_1400.plusHours(4), 180,
                        BASE_68, EXTRA_20, 60,
                        List.of(always(1, "倍率 1.333", 10, false, Mode.MULTIPLY, "1.333")), "90.64", "26.66", "117.30"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pricingTable")
    @DisplayName("表驱动：给定区间与规则，金额逐列对上")
    void pricesMatchTable(String name, LocalDateTime start, LocalDateTime end, int coveredMinutes,
                          BigDecimal basePrice, BigDecimal extraHourPrice, int unitMinutes,
                          List<RateRuleDef> rules,
                          String expectBase, String expectOverage, String expectTotal) {
        PriceCalculator.Quote q = PriceCalculator.price(
                new PriceCalculator.Input(start, end, coveredMinutes, basePrice, extraHourPrice, unitMinutes, rules));

        assertThat(q.baseAmount()).as(name + " 的起步金额").isEqualByComparingTo(expectBase);
        assertThat(q.overageAmount()).isEqualByComparingTo(expectOverage);
        assertThat(q.total()).isEqualByComparingTo(expectTotal);
    }

    @Test
    @DisplayName("基准日期的星期假设先钉住，否则后面几十条会静默测错")
    void fixtureDatesAreWhatTheyClaim() {
        assertThat(LocalDate.of(2026, 10, 5).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(LocalDate.of(2026, 10, 8).getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(LocalDate.of(2026, 10, 9).getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(LocalDate.of(2026, 10, 10).getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(LocalDate.of(2026, 10, 11).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    }

    // ------------------------------------------------------------ 明细的结构

    @Nested
    class Lines {

        @Test
        @DisplayName("合计永远等于打印出来的分项相加，哪怕粒度取整产生零头")
        void totalIsExactlyTheSumOfPrintedLines() {
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(4).plusMinutes(7), 180,
                    BASE_68, new BigDecimal("19.99"), 7,
                    List.of(onDays(1, "周末", 100, false, Mode.MULTIPLY, "1.37", DayOfWeek.MONDAY))));

            BigDecimal printed = q.lines().stream()
                    .map(PriceCalculator.Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(printed).isEqualByComparingTo(q.total());
        }

        @Test
        @DisplayName("连续同价的单元并成一条明细，价钱不变")
        void consecutiveUnitsOfTheSamePriceMergeIntoOneLine() {
            // 4 小时超时、粒度 60，平时价 → 起步 + 一条超时明细
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(7), 180, BASE_68, EXTRA_20, 60, List.of()));

            assertThat(q.lines()).hasSize(2);
            assertThat(q.lines().get(1).kind()).isEqualTo("OVERAGE");
            assertThat(q.lines().get(1).chargeMinutes()).isEqualTo(240);
            assertThat(q.lines().get(1).label()).startsWith("超时费 ");
            assertThat(q.overageUnits()).isEqualTo(4);   // 并档只改展示，单元数仍记着
            assertThat(q.total()).isEqualByComparingTo("148.00");
        }

        @Test
        @DisplayName("规则切换处必须断开成两条，账单才说得出哪一段用了哪条规则")
        void lineSplitsWhereTheRuleChanges() {
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    LocalDateTime.of(2026, 10, 9, 22, 0), LocalDateTime.of(2026, 10, 10, 1, 0), 60,
                    BASE_68, EXTRA_20, 60,
                    List.of(onDays(1, "周末上浮", 100, false, Mode.MULTIPLY, "1.20", DayOfWeek.SATURDAY))));

            List<PriceCalculator.Line> tail = q.lines().stream()
                    .filter(l -> "OVERAGE".equals(l.kind())).toList();
            assertThat(tail).hasSize(2);
            assertThat(tail.get(0).ruleName()).isNull();
            assertThat(tail.get(0).unitPrice()).isEqualByComparingTo("20.00");
            assertThat(tail.get(1).ruleName()).isEqualTo("周末上浮");
            assertThat(tail.get(1).unitPrice()).isEqualByComparingTo("24.00");
        }

        @Test
        @DisplayName("起步段没有超时就不该出现超时明细")
        void noOverageLineWhenStayIsWithinCover() {
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusMinutes(90), 180, BASE_68, EXTRA_20, 60, List.of()));

            assertThat(q.lines()).hasSize(1);
            assertThat(q.lines().get(0).kind()).isEqualTo("BASE");
            assertThat(q.overageAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("起步价是打包价，明细要标出来，否则客人会按小时价去乘")
        void baseLineIsLabelledAsAPackagePrice() {
            PriceCalculator.Line base = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(3), 180, BASE_68, EXTRA_20, 60, List.of()))
                    .lines().get(0);

            assertThat(base.unitHint()).isEqualTo("打包");
            assertThat(base.label()).contains("3 小时");
        }
    }

    // ---------------------------------------------------------------- 规则决胜

    @Nested
    class RuleChoice {

        @Test
        @DisplayName("priority 数字小的赢")
        void lowerPriorityNumberWins() {
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(3), 180, BASE_68, EXTRA_20, 60,
                    List.of(always(1, "活动价", 200, false, Mode.OVERRIDE, "50.00"),
                            always(2, "店长指定价", 50, false, Mode.OVERRIDE, "45.00"))));

            assertThat(q.baseAmount()).isEqualByComparingTo("45.00");
            assertThat(q.lines().get(0).ruleName()).isEqualTo("店长指定价");
        }

        @Test
        @DisplayName("同 priority 时房型级压过民宿级")
        void roomTypeScopedBeatsPropertyWide() {
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(3), 180, BASE_68, EXTRA_20, 60,
                    List.of(always(1, "民宿级", 100, false, Mode.OVERRIDE, "50.00"),
                            always(2, "房型级", 100, true, Mode.OVERRIDE, "40.00"))));

            assertThat(q.baseAmount()).isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("同 priority 同作用域时按 id 决胜，且与传入顺序无关")
        void tieBreaksOnIdNotListOrder() {
            List<RateRuleDef> asc = List.of(always(1, "id 小", 100, false, Mode.OVERRIDE, "50.00"),
                    always(2, "id 大", 100, false, Mode.OVERRIDE, "45.00"));
            List<RateRuleDef> desc = List.of(asc.get(1), asc.get(0));

            PriceCalculator.Input in = new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(3), 180, BASE_68, EXTRA_20, 60, desc);
            // 决胜不唯一的话，同一张单两次算出两个价，快照就成了看运气
            assertThat(PriceCalculator.price(in).baseAmount()).isEqualByComparingTo("50.00");
            assertThat(PriceCalculator.price(
                    new PriceCalculator.Input(MON_1400, MON_1400.plusHours(3), 180,
                            BASE_68, EXTRA_20, 60, asc)).baseAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("一条都不命中时回到平时价")
        void fallsBackToNormalPrices() {
            // 日期故意挑在国庆之后：MON_1400 是 10-05，还在假期内，用 10-01~10-07 会"意外命中"
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    MON_1400, MON_1400.plusHours(3), 180, BASE_68, EXTRA_20, 60,
                    List.of(onDates(1, "元旦", 10, false, Mode.OVERRIDE, "488.00",
                            "2026-12-30", "2027-01-02"))));

            assertThat(q.baseAmount()).isEqualByComparingTo("68.00");
            assertThat(q.lines().get(0).ruleName()).isNull();
        }

        @Test
        @DisplayName("日期区间是闭区间：首日与末日都命中，末日之后不命中")
        void dateRangeIsInclusive() {
            List<RateRuleDef> rules = List.of(onDates(1, "国庆", 10, false, Mode.OVERRIDE,
                    "488.00", "2026-10-01", "2026-10-07"));

            assertThat(hit(rules, LocalDateTime.of(2026, 10, 1, 14, 0))).isNotNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 7, 14, 0))).isNotNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 8, 14, 0))).isNull();
        }

        @Test
        @DisplayName("跨零点的时段窗口：23:00 与 01:00 都算命中，02:00 整不算")
        void timeWindowWrapsMidnight() {
            List<RateRuleDef> rules = List.of(inHours(1, "深夜", 10, false, Mode.MULTIPLY,
                    "1.50", "22:00", "02:00"));

            assertThat(hit(rules, LocalDateTime.of(2026, 10, 5, 23, 0))).isNotNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 6, 1, 0))).isNotNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 6, 2, 0))).isNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 5, 12, 0))).isNull();
        }

        @Test
        @DisplayName("只到 02:00 的凌晨窗口不该把白天一起改了")
        void nonWrappingWindowStaysInsideItself() {
            List<RateRuleDef> rules = List.of(inHours(1, "白天特价", 10, false, Mode.OVERRIDE,
                    "15.00", "09:00", "12:00"));

            assertThat(hit(rules, LocalDateTime.of(2026, 10, 5, 9, 0))).isNotNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 5, 11, 59))).isNotNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 5, 12, 0))).isNull();
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 5, 18, 0))).isNull();
        }

        @Test
        @DisplayName("日期、星期、时段三块都给时必须同时满足")
        void allGivenConditionsMustHold() {
            List<RateRuleDef> rules = List.of(new RateRuleDef(1L, "国庆周末夜间", Set.of(DayOfWeek.SATURDAY),
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7),
                    LocalTime.of(22, 0), LocalTime.of(2, 0), Mode.MULTIPLY, new BigDecimal("1.50"),
                    10, false, AppliesTo.ALL));

            assertThat(hit(rules, LocalDateTime.of(2026, 10, 3, 23, 0))).isNotNull();   // 周六 ∧ 区间内 ∧ 窗口内
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 4, 23, 0))).isNull();      // 周日，星期不符
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 3, 15, 0))).isNull();      // 时段不符
            assertThat(hit(rules, LocalDateTime.of(2026, 10, 10, 23, 0))).isNull();     // 日期不符
        }

        private RateRuleDef hit(List<RateRuleDef> rules, LocalDateTime at) {
            return PriceCalculator.selectBest(rules, at, AppliesTo.ALL);
        }
    }

    // ---------------------------------------------------- 规则改的是哪一笔钱

    /**
     * 审阅第 5 条举的那个例子，原样搬进来当回归用例：
     * 平时全日 388、超时 40/小时、假日"一口价 488"、客人延迟退房 1 小时。
     *
     * <p>房费是打包价、超时是元/小时，一条规则同时套两种量纲会收出 488 + 488 = 976，
     * 而规则名字明明写的是"房费 488"。现在由 {@code appliesTo} 决定命中哪一头，
     * 默认 ALL 保留旧行为（所以这里三种都测一遍，把差异钉在测试里而不是靠记忆）。
     */
    @Nested
    class AppliesToTarget {

        /** 10-03 14:00 入住、次日 13:00 退房 = 模板 22 小时 + 超时 1 小时。 */
        private static final LocalDateTime START = LocalDateTime.of(2026, 10, 3, 14, 0);
        private static final LocalDateTime END = LocalDateTime.of(2026, 10, 4, 13, 0);

        private PriceCalculator.Quote priceWith(AppliesTo appliesTo) {
            return PriceCalculator.price(new PriceCalculator.Input(START, END, 1320,
                    new BigDecimal("388.00"), new BigDecimal("40.00"), 60,
                    List.of(rule(1, "国庆全日房一口价", 10, true, Mode.OVERRIDE,
                            "488.00", appliesTo))));
        }

        @Test
        @DisplayName("只改房费时，超时仍按平时小时价收 —— 488 + 40")
        void baseOnlyLeavesHourlyPriceAlone() {
            PriceCalculator.Quote q = priceWith(AppliesTo.BASE);
            assertThat(q.baseAmount()).isEqualByComparingTo("488.00");
            assertThat(q.overageAmount()).isEqualByComparingTo("40.00");
            assertThat(q.total()).isEqualByComparingTo("528.00");
        }

        @Test
        @DisplayName("ALL 会把小时价也变成 488 —— 这正是 976 那个意外，留着当对照")
        void allAppliesToBothLineKinds() {
            PriceCalculator.Quote q = priceWith(AppliesTo.ALL);
            assertThat(q.baseAmount()).isEqualByComparingTo("488.00");
            assertThat(q.overageAmount()).isEqualByComparingTo("488.00");
            assertThat(q.total()).isEqualByComparingTo("976.00");
        }

        @Test
        @DisplayName("只改超时单价时房费不动 —— 388 + 488")
        void overageOnlyLeavesBaseAlone() {
            PriceCalculator.Quote q = priceWith(AppliesTo.OVERAGE);
            assertThat(q.baseAmount()).isEqualByComparingTo("388.00");
            assertThat(q.overageAmount()).isEqualByComparingTo("488.00");
            assertThat(q.total()).isEqualByComparingTo("876.00");
        }

        @Test
        @DisplayName("BASE 规则不参与超时段决胜，才能让位给一条 OVERAGE 规则")
        void baseRuleDoesNotWinTheOverageSlot() {
            PriceCalculator.Quote q = PriceCalculator.price(new PriceCalculator.Input(
                    START, END, 1320, new BigDecimal("388.00"), new BigDecimal("40.00"), 60,
                    List.of(rule(1, "假日房费", 5, true, Mode.OVERRIDE, "488.00", AppliesTo.BASE),
                            rule(2, "假日超时单价", 50, true, Mode.OVERRIDE, "60.00", AppliesTo.OVERAGE))));

            assertThat(q.baseAmount()).isEqualByComparingTo("488.00");
            assertThat(q.overageAmount()).isEqualByComparingTo("60.00");
            assertThat(q.total()).isEqualByComparingTo("548.00");
        }
    }

    // ------------------------------------------------------------------ 入参

    @Test
    @DisplayName("非法入参直接抛，不静默给个 0 元账单")
    void rejectsIllegalInput() {
        assertThatThrownBy(() -> PriceCalculator.price(new PriceCalculator.Input(
                MON_1400, MON_1400.minusMinutes(1), 180, BASE_68, EXTRA_20, 60, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("半开区间");

        assertThatThrownBy(() -> PriceCalculator.price(new PriceCalculator.Input(
                MON_1400, MON_1400.plusHours(3), 180, BASE_68, EXTRA_20, 0, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计费粒度");
    }

    @Test
    @DisplayName("分钟数写成给人看的话：1320 是 22 小时，150 是 2 小时 30 分钟")
    void rendersMinutesForHumans() {
        assertThat(PriceCalculator.minutes(1320)).isEqualTo("22 小时");
        assertThat(PriceCalculator.minutes(150)).isEqualTo("2 小时 30 分钟");
        assertThat(PriceCalculator.minutes(45)).isEqualTo("45 分钟");
    }

    // ---------------------------------------------------------------- 规则构造

    private static RateRuleDef always(long id, String name, int priority, boolean scoped,
                                      Mode mode, String value) {
        return rule(id, name, priority, scoped, mode, value, AppliesTo.ALL);
    }

    /** 显式指定"改的是哪一笔钱"，用来测 applies_to 的分流。 */
    private static RateRuleDef rule(long id, String name, int priority, boolean scoped,
                                    Mode mode, String value, AppliesTo appliesTo) {
        return new RateRuleDef(id, name, EnumSet.noneOf(DayOfWeek.class), null, null, null, null,
                mode, new BigDecimal(value), priority, scoped, appliesTo);
    }

    // ------------------------------------------------------------------ 连住

    @Nested
    @DisplayName("全日房连住：每晚各自按自己那一晚的起点定价")
    class Nights {

        /** 模板一晚 14:00 → 次日 12:00 = 1320 分；N 晚的覆盖 = 24h×(N-1) + 1320 */
        private final int night = 1320;
        private final BigDecimal base388 = new BigDecimal("388.00");
        private final BigDecimal extra40 = new BigDecimal("40.00");
        private final LocalDateTime mon = LocalDateTime.of(2026, 10, 5, 14, 0);

        private PriceCalculator.Input in(LocalDateTime start, int nights, LocalDateTime end,
                                         List<RateRuleDef> rules) {
            return in(start, nights, end, rules, 1440 * (nights - 1) + night);
        }

        private PriceCalculator.Input in(LocalDateTime start, int nights, LocalDateTime end,
                                         List<RateRuleDef> rules, int coveredMinutes) {
            return new PriceCalculator.Input(start, end, coveredMinutes, nights,
                    base388, extra40, 60, rules);
        }

        private List<PriceCalculator.Line> baseLines(PriceCalculator.Quote q) {
            return q.lines().stream().filter(l -> "BASE".equals(l.kind())).toList();
        }

        @Test
        @DisplayName("三晚、没有规则：出三条起步明细，合计 = 3 × 388")
        void threeNightsNoRules() {
            PriceCalculator.Quote q = PriceCalculator.price(
                    in(mon, 3, mon.plusMinutes(1440 * 2 + night), List.of()));

            assertThat(baseLines(q)).hasSize(3);
            assertThat(baseLines(q)).allSatisfy(l -> assertThat(l.unitHint()).isEqualTo("元/晚"));
            assertThat(q.baseAmount()).isEqualByComparingTo("1164.00");
            assertThat(q.overageAmount()).isEqualByComparingTo("0.00");
            assertThat(q.total()).isEqualByComparingTo("1164.00");
        }

        @Test
        @DisplayName("跨国庆边界的五晚：国庆内三晚各 488、外面两晚各 388 —— 不是整单一个价")
        void perNightPricingAcrossHolidayBoundary() {
            List<RateRuleDef> rules = List.of(
                    onDates(1, "国庆一口价", 10, true, Mode.OVERRIDE, "488.00", "2026-10-01", "2026-10-07"));

            PriceCalculator.Quote q = PriceCalculator.price(
                    in(mon, 5, mon.plusMinutes(1440 * 4 + night), rules));

            // 10-05/06/07 三晚命中一口价，10-08/09 两晚走平时价。
            // 若按"入住那天定价一次再乘 N"，这里会是 2440 或 1940 —— 两个都不对
            assertThat(baseLines(q)).extracting(PriceCalculator.Line::ruleName)
                    .containsExactly("国庆一口价", "国庆一口价", "国庆一口价", null, null);
            assertThat(baseLines(q)).extracting(PriceCalculator.Line::amount)
                    .containsExactly(new BigDecimal("488.00"), new BigDecimal("488.00"),
                            new BigDecimal("488.00"), new BigDecimal("388.00"), new BigDecimal("388.00"));
            assertThat(q.total()).isEqualByComparingTo("2240.00");
        }

        @Test
        @DisplayName("周末倍率只改写落在周末的那两晚（周五入住连住三晚）")
        void weekendRateAppliesOnlyToWeekendNights() {
            List<RateRuleDef> rules = List.of(
                    onDays(1, "周末上浮", 100, false, Mode.MULTIPLY, "1.20",
                            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
            LocalDateTime fri = LocalDateTime.of(2026, 10, 9, 14, 0);

            PriceCalculator.Quote q = PriceCalculator.price(
                    in(fri, 3, fri.plusMinutes(1440 * 2 + night), rules));

            // 388 + 465.60 + 465.60：周五那晚不打折
            assertThat(baseLines(q)).extracting(PriceCalculator.Line::amount)
                    .containsExactly(new BigDecimal("388.00"), new BigDecimal("465.60"),
                            new BigDecimal("465.60"));
            assertThat(q.total()).isEqualByComparingTo("1319.20");
        }

        @Test
        @DisplayName("N 条起步明细金额之和恒等于起步合计，也恒等于合计减超时")
        void nightLineAmountsAlwaysSumUp() {
            List<RateRuleDef> rules = List.of(
                    onDates(1, "国庆一口价", 10, true, Mode.OVERRIDE, "488.00", "2026-10-01", "2026-10-07"),
                    onDays(2, "周末上浮", 100, false, Mode.MULTIPLY, "1.20",
                            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
            LocalDateTime coveredEnd = mon.plusMinutes(1440 * 6 + night);

            PriceCalculator.Quote q = PriceCalculator.price(in(mon, 7, coveredEnd.plusHours(5), rules));

            BigDecimal sum = baseLines(q).stream().map(PriceCalculator.Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(q.baseAmount());
            assertThat(q.baseAmount().add(q.overageAmount())).isEqualByComparingTo(q.total());
            // 晚与晚之间那些 12:00–14:00 含在房费里、不是独立计费单元，所以分钟数之和不等于覆盖时长
            assertThat(baseLines(q)).hasSize(7);
            assertThat(baseLines(q).stream().mapToInt(PriceCalculator.Line::chargeMinutes).sum())
                    .isEqualTo(7 * night);
        }

        @Test
        @DisplayName("住满三晚再拖到次日 15:00：三晚房费 + 3 小时超时，超时从最后一晚终点起算")
        void lateCheckoutBillsOvertimeAfterAllNights() {
            LocalDateTime coveredEnd = mon.plusMinutes(1440 * 2 + night);

            PriceCalculator.Quote q = PriceCalculator.price(in(mon, 3, coveredEnd.plusHours(3), List.of()));

            assertThat(q.baseAmount()).isEqualByComparingTo("1164.00");
            assertThat(q.overageAmount()).isEqualByComparingTo("120.00");
            assertThat(q.total()).isEqualByComparingTo("1284.00");
        }

        @Test
        @DisplayName("晚数与覆盖时长不自洽要直接抛，不该算出一个看不懂的价")
        void rejectsInconsistentNights() {
            LocalDateTime end = mon.plusMinutes(1440 * 2 + night);
            // 显式给一个合法的覆盖时长：辅助方法按晚数反算 covered，传 0 会先撞上"覆盖为负"那条
            assertThatThrownBy(() -> PriceCalculator.price(in(mon, 0, end, List.of(), night)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("连住晚数");

            // 说 3 晚却只给一晚的覆盖时长：反推出来一晚是负数分钟
            assertThatThrownBy(() -> PriceCalculator.price(in(mon, 3, end, List.of(), night)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不自洽");
        }
    }

    private static RateRuleDef onDays(long id, String name, int priority, boolean scoped,
                                      Mode mode, String value, DayOfWeek... days) {
        return new RateRuleDef(id, name, EnumSet.copyOf(Set.of(days)), null, null, null, null,
                mode, new BigDecimal(value), priority, scoped, AppliesTo.ALL);
    }

    private static RateRuleDef onDates(long id, String name, int priority, boolean scoped,
                                      Mode mode, String value, String from, String to) {
        return new RateRuleDef(id, name, EnumSet.noneOf(DayOfWeek.class),
                LocalDate.parse(from), LocalDate.parse(to), null, null,
                mode, new BigDecimal(value), priority, scoped, AppliesTo.ALL);
    }

    private static RateRuleDef inHours(long id, String name, int priority, boolean scoped,
                                      Mode mode, String value, String from, String to) {
        return new RateRuleDef(id, name, EnumSet.noneOf(DayOfWeek.class), null, null,
                LocalTime.parse(from), LocalTime.parse(to),
                mode, new BigDecimal(value), priority, scoped, AppliesTo.ALL);
    }
}
