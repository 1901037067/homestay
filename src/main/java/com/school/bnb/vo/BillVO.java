package com.school.bnb.vo;

import com.school.bnb.billing.PriceCalculator;
import com.school.bnb.entity.Bill;
import com.school.bnb.entity.Booking;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单/试算的统一返回体。
 *
 * <p>{@link #snapshot} 是这份数据的身份：<b>true = 已结算账单的快照，false = 按当前规则实时算的价</b>。
 * 两者金额可以不同 —— 规则改了、房型价改了，历史账单都不会跟着变。
 * 前端必须把区别显示出来，否则前台会以为"系统算错了钱"。
 *
 * <p>两个工厂方法而不是让调用方按位置构造 18 个参数：位置传错一个，
 * 编译器完全看不出来，而错的会是金额。
 */
public record BillVO(
        Long bookingId,
        String bookingNo,
        String roomNo,
        String guestName,
        String saleType,
        String status,
        Boolean snapshot,
        LocalDateTime calcStart,
        LocalDateTime calcEnd,
        Integer coveredMinutes,
        Integer chargeUnitMinutes,
        BigDecimal baseAmount,
        BigDecimal overageAmount,
        BigDecimal totalAmount,
        List<Line> lines,
        /** 口径提示：早退不退、超时补收、尚未退房只按预订区间算。 */
        String notice,
        LocalDateTime settledAt,
        String operatorName) {

    /**
     * 一条计费明细。
     *
     * @param unitHint "打包" 表示这一条是一次性价而不是小时价 —— 客人照着单价乘分钟数会算出另一个数
     * @param ruleName 命中的价格规则；null 表示走平时价
     */
    public record Line(String kind,
                       String label,
                       LocalDateTime from,
                       LocalDateTime to,
                       Integer chargeMinutes,
                       BigDecimal unitPrice,
                       String unitHint,
                       String ruleName,
                       BigDecimal amount) {

        public static Line of(PriceCalculator.Line l) {
            return new Line(l.kind(), l.label(), l.from(), l.to(), l.chargeMinutes(),
                    l.unitPrice(), l.unitHint(), l.ruleName(), l.amount());
        }
    }

    public static BillVO ofQuote(Booking b, String roomNo, int coveredMinutes, int chargeUnitMinutes,
                                 LocalDateTime calcEnd, String notice, PriceCalculator.Quote q) {
        return new BillVO(b.getId(), b.getBookingNo(), roomNo, b.getGuestName(), b.getSaleType(),
                b.getStatus(), false, b.getStartTime(), calcEnd, coveredMinutes, chargeUnitMinutes,
                q.baseAmount(), q.overageAmount(), q.total(),
                q.lines().stream().map(Line::of).toList(), notice, null, null);
    }

    public static BillVO ofBill(Bill bill, Booking b, List<Line> lines, String notice) {
        return new BillVO(b.getId(), b.getBookingNo(), bill.getRoomNo(), bill.getGuestName(),
                bill.getSaleType(), b.getStatus(), true, bill.getCalcStart(), bill.getCalcEnd(),
                bill.getCoveredMinutes(), bill.getChargeUnitMinutes(),
                bill.getBaseAmount(), bill.getOverageAmount(), bill.getTotalAmount(),
                lines, notice, bill.getSettledAt(), bill.getOperatorName());
    }
}
