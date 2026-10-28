package com.school.bnb.billing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * 一条价格规则喂给纯函数 {@link PriceCalculator} 时需要的样子。
 *
 * <p>注意这里<b>没有 enabled 字段</b> —— 停用规则在加载阶段就被滤掉了，不下发到计费层。
 * 让"启停"这个概念只存在于 SQL 的 where 里，纯函数就少一个分支、也少一类"忘了判 enabled"的 bug。
 *
 * <p>也没有 propertyId / roomTypeId：作用域（这间房的房型 + 这家民宿）在加载时已经筛完，
 * 传进来的都是候选命中集。纯函数只回答"这个时刻该用哪个价"。
 *
 * @param id            决胜用的稳定次序（规则改不了 id）
 * @param name          账单快照里要记下"当时命中了哪条"，否则两个月后看不懂这张单怎么来的
 * @param weekdays      空集 = 不限星期
 * @param fromDate      null = 不限日期；此时 toDate 一并忽略
 * @param toDate        null = 只覆盖 fromDate 当天
 * @param startHour     null = 全天
 * @param endHour       null = 全天。{@code startHour > endHour} 表示跨零点窗口（22:00→02:00）
 * @param roomTypeScoped true = 房型级规则，同优先级时压过民宿级
 */
public record RateRuleDef(Long id,
                          String name,
                          Set<DayOfWeek> weekdays,
                          LocalDate fromDate,
                          LocalDate toDate,
                          LocalTime startHour,
                          LocalTime endHour,
                          Mode mode,
                          BigDecimal value,
                          int priority,
                          boolean roomTypeScoped) {

    /**
     * 计价方式。只有这两种，故意不做"满减""固定服务费"之类 ——
     * 每多一种模式，账单快照的可解释性和单测的组合数都翻倍。
     */
    public enum Mode {
        /** 命中的那段，单价直接等于 value。全日房节假日一口价用它。 */
        OVERRIDE,
        /** 命中的那段，在平时价上乘 value（1.20 = 上浮 20%，0.85 = 打 85 折）。 */
        MULTIPLY
    }

    /**
     * 这个时刻是否落进本规则。三个维度都给时必须<b>同时</b>满足（日期 ∧ 星期 ∧ 时段）。
     *
     * <p>时段按半开区间 {@code [startHour, endHour)} 判 —— 和全项目的区间口径一致，
     * 所以"22:00 整退房"不会沾上 22:00 开始的夜间加价。
     */
    public boolean matches(LocalDateTime at) {
        if (at == null) {
            return false;
        }
        LocalDate d = at.toLocalDate();
        if (fromDate != null) {
            LocalDate to = toDate == null ? fromDate : toDate;
            if (d.isBefore(fromDate) || d.isAfter(to)) {
                return false;
            }
        }
        if (!weekdays.isEmpty() && !weekdays.contains(at.getDayOfWeek())) {
            return false;
        }
        return inTimeWindow(at.toLocalTime());
    }

    private boolean inTimeWindow(LocalTime tod) {
        if (startHour == null || endHour == null) {
            return true;
        }
        if (startHour.equals(endHour)) {
            return true;   // 两端相同视为全天，避免配成"一个点"导致规则永不命中
        }
        return startHour.isBefore(endHour)
                ? !tod.isBefore(startHour) && tod.isBefore(endHour)          // 常规窗口
                : !tod.isBefore(startHour) || tod.isBefore(endHour);         // 跨零点窗口
    }

    /** 命中后把平时价变成什么价。乘法在这里做，取整留给调用方，免得两处口径不一致。 */
    public BigDecimal applyTo(BigDecimal normalPrice) {
        return switch (mode) {
            case OVERRIDE -> value;
            case MULTIPLY -> normalPrice.multiply(value);
        };
    }
}
