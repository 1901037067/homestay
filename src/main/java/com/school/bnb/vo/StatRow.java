package com.school.bnb.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 报表取数用的<b>一行原始订单</b> —— 只带算得动聚合所需的这几列。
 *
 * <p>为什么不直接在 SQL 里 group by：营业日切割、跨零点摊到小时桶、超时/早退判定这三件事
 * 都要"先归一化再分桶"，写成一条 SQL 会没人读得懂，而且<b>没法单测</b> ——
 * 每个分支都得连库造数据。本项目已经在计费上靠"纯函数 + 表驱动用例"拿到过好处
 * （{@code PriceCalculator}），报表沿用同一个形状：SQL 只做范围扫描，
 * 切割与分桶交给 {@link com.school.bnb.stats.StatsAggregator}。
 *
 * <p>数据量也站得住：单机毕设，一年也就几千行，扫进内存比写复杂 SQL 便宜得多。
 * 真要长大，把聚合下推回 SQL 的改动只在这一个边界上。
 */
@Data
public class StatRow {

    private Long bookingId;
    private Long roomId;
    private Long propertyId;
    private String roomNo;
    private String saleType;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime actualCheckOut;

    /**
     * 连住晚数，决定这一单要给几个营业日各记一次房晚。
     *
     * <p>SQL 里已经 {@code coalesce(b.nights, 1)}，所以正常不会是 null；这里仍按
     * "缺失当 1 晚"处理 —— 少算房晚比多算更难被发现，宁可让兜底是保守的那一侧。
     */
    private Integer nights;

    /** 已结算金额；未结算为 0（下单时写 0，结算时由计费引擎写回）。 */
    private BigDecimal amount;
}
