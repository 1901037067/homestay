package com.school.bnb.vo;

import com.school.bnb.entity.RateRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 价格规则的对外形状。weekdays 还原成 1..7 列表，前端不必再拆字符串。 */
public record RateRuleVO(
        Long id,
        Long propertyId,
        String propertyName,
        Long roomTypeId,
        String roomTypeName,
        String name,
        LocalDate validFrom,
        LocalDate validTo,
        List<Integer> weekdays,
        LocalTime startHour,
        LocalTime endHour,
        String mode,
        BigDecimal value,
        Integer priority,
        Boolean enabled,
        /** 一句人话概括命中条件，表格列直接显示它，省得前端再拼一遍规则。 */
        String scope) {

    public static RateRuleVO of(RateRule r, String propertyName, String roomTypeName, List<Integer> weekdays) {
        return new RateRuleVO(r.getId(), r.getPropertyId(), propertyName, r.getRoomTypeId(), roomTypeName,
                r.getName(), r.getValidFrom(), r.getValidTo(), weekdays, r.getStartHour(), r.getEndHour(),
                r.getMode(), r.getValue(), r.getPriority(), r.getEnabled(), describe(r, weekdays));
    }

    private static final String[] CN_WEEKDAY = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static String describe(RateRule r, List<Integer> weekdays) {
        StringBuilder sb = new StringBuilder();
        if (r.getValidFrom() != null) {
            sb.append(r.getValidTo() == null || r.getValidTo().equals(r.getValidFrom())
                    ? r.getValidFrom() + " 当天"
                    : r.getValidFrom() + " ~ " + r.getValidTo());
        }
        if (weekdays != null && !weekdays.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(weekdays.stream().map(d -> CN_WEEKDAY[d - 1]).collect(java.util.stream.Collectors.joining("、")));
        }
        if (r.getStartHour() != null && r.getEndHour() != null) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(r.getStartHour()).append("–").append(r.getEndHour()).append(" 时段");
        }
        if (sb.isEmpty()) {
            sb.append("全天");
        }
        return sb.append("｜").append(r.getRoomTypeId() == null ? "民宿通用" : "仅指定房型").toString();
    }
}
