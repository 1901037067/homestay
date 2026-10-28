package com.school.bnb.booking;

/**
 * 售卖方式。
 *
 * <p>为什么要有这个枚举：原先用"请求是否跨天"来推断是不是全日房，是错的 ——
 * 跨天不等于全日房。22:00 → 次日 00:30 只有 2.5 小时，却因为跨天绕过了起步时长校验。
 * 推断改声明，规则才有裁判。
 */
public enum SaleType {

    /** 钟点：受 min_hours 与可售窗口两端约束，必须当天开始当天结束。 */
    HOURLY,

    /** 全日房模板：区间必须与房型的 full_day_start / full_day_end 完全一致，不受 min_hours 约束。 */
    FULL_DAY
}
