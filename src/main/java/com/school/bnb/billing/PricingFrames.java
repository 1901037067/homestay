package com.school.bnb.billing;

import com.school.bnb.booking.SaleType;
import com.school.bnb.booking.SellPolicy;
import com.school.bnb.entity.RoomType;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 算账需要的两个<b>房型结构参数</b>：起步覆盖时长与计费粒度。
 *
 * <p>单独抽出来是因为它有两个使用时机，且必须给出同一个答案：
 * <ul>
 *   <li><b>下单时</b>把结果快照到 {@code booking.covered_minutes / charge_unit_minutes}；</li>
 *   <li><b>结算时</b>优先读快照，历史数据没有快照才现算（退回现算 = 升级前行为）。</li>
 * </ul>
 * 如果两处各写一遍，改配置之后"快照"和"现算"会悄悄分叉，而这正是要防的问题本身。
 */
public final class PricingFrames {

    private PricingFrames() {
    }

    public record Frames(int coveredMinutes, int chargeUnitMinutes) {
    }

    /**
     * @param saleType 钟点房按 {@code min_hours}；全日房按房型模板时长（14:00→次日 12:00 即 22 小时），
     *                 因为 base_price 买的正是这一整段，只有加时才该按超时单价收
     */
    public static Frames of(SaleType saleType, RoomType type, LocalDateTime start) {
        int covered;
        if (saleType == SaleType.FULL_DAY) {
            LocalDateTime[] tpl = SellPolicy.fullDayRange(start.toLocalDate(),
                    type.getFullDayStart(), type.getFullDayEnd());
            covered = (int) Duration.between(tpl[0], tpl[1]).toMinutes();
        } else {
            covered = type.getMinHours() * 60;
        }
        // 脏值（null / 0 / 负）兜回 60：历史房型数据可能没有这一列，报价不该因为一个空值挂掉
        int unit = type.getChargeUnitMinutes() == null || type.getChargeUnitMinutes() < 1
                ? 60 : type.getChargeUnitMinutes();
        return new Frames(covered, unit);
    }
}
