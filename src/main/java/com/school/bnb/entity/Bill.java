package com.school.bnb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 账单快照 —— 结算那一刻"这笔钱怎么来的"永久留档。
 *
 * <p>为什么要留这么多列而不是只留 total：规则和房型价以后会改，
 * 只存总额的话，两个月后这张单到底按什么价算的就再也查不回来了。
 * {@link #detailJson} 存逐条明细（含当时命中的规则名），
 * 三个金额列是冗余，为的是报表直接 sum 而不用解析 JSON。
 *
 * <p>{@code booking_id} 唯一：一张单只有一张账单。SETTLED 是状态机终态，
 * 真要把钱改回来走人工另开单，不能覆盖这份快照。
 */
@Data
@TableName("bill")
public class Bill {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bookingId;

    /** 房号快照：房间以后改了编号，历史账单上仍是当时那个门牌。 */
    private String roomNo;
    private String guestName;
    private String saleType;

    private LocalDateTime calcStart;
    private LocalDateTime calcEnd;
    private Integer coveredMinutes;
    private Integer chargeUnitMinutes;

    private BigDecimal baseAmount;
    private BigDecimal overageAmount;
    private BigDecimal totalAmount;
    private String detailJson;

    private LocalDateTime settledAt;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime createdAt;
}
