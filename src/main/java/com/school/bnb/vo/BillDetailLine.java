package com.school.bnb.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code bill.detail_json} 里的一条明细，只取分摊营收需要的那三列。
 *
 * <p>为什么从账单快照读、而不是现算：营收的分摊必须与"当时算出来的钱"同源。
 * 用现在的规则重算一遍再按月切，等于让报表跟着规则改而漂移 ——
 * 而"已结算账单不回溯"是这个项目从 V7 起就钉住的口径。
 */
public record BillDetailLine(LocalDateTime from, LocalDateTime to, BigDecimal amount) {
}
