package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

/**
 * 价格规则。作用域是 (民宿 + 可选房型)，命中条件是 (日期区间 + 星期 + 日内时段) 三块可选与关系。
 *
 * <p>{@link #weekdays} 存 "6,7" 这样的可读字符串而不是位掩码：规则要人在后台对着看，
 * 一个十进制整数在数据库客户端里根本读不出"周六周日"。解析集中在 RateRuleService。
 */
@Data
@TableName("rate_rule")
public class RateRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long propertyId;

    /** null = 整家民宿通用；非空 = 只作用于该房型，同优先级时压过民宿级。 */
    private Long roomTypeId;
    private String name;

    private LocalDate validFrom;
    private LocalDate validTo;
    private String weekdays;
    private LocalTime startHour;
    private LocalTime endHour;

    /** OVERRIDE / MULTIPLY，见 {@link com.school.bnb.billing.RateRuleDef.Mode}。 */
    private String mode;
    private BigDecimal value;
    private Integer priority;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
