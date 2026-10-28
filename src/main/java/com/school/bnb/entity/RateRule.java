package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
 *
 * <p><b>那几个"留空即不限"的列必须标 {@code updateStrategy = ALWAYS}。</b>
 * MyBatis-Plus 默认的 NOT_NULL 策略会把 null 字段从 UPDATE 的 SET 里整个跳过 ——
 * 于是"周末限定改成不限星期""指定房型改成民宿通用"这类清空操作会<b>保存成功而库里没变</b>，
 * 旧限制继续悄悄影响计价。这是外部审阅直接查了生成的 SQL 才确认下来的，
 * 光看 Java 代码和页面提示都发现不了。
 */
@Data
@TableName("rate_rule")
public class RateRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long propertyId;

    /** null = 整家民宿通用；非空 = 只作用于该房型，同优先级时压过民宿级。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long roomTypeId;
    private String name;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate validFrom;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate validTo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String weekdays;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalTime startHour;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalTime endHour;

    /** OVERRIDE / MULTIPLY，见 {@link com.school.bnb.billing.RateRuleDef.Mode}。 */
    private String mode;
    private BigDecimal value;

    /** ALL / BASE / OVERAGE，见 {@link com.school.bnb.billing.RateRuleDef.AppliesTo}。 */
    private String appliesTo;
    private Integer priority;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
