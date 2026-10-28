package com.school.bnb.booking;

import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.entity.Booking;
import java.util.Map;
import java.util.Set;

/**
 * 预订状态机（实施计划 3.5）。跃迁表集中在这里，散在各 service 里的 if 判断迟早会互相矛盾。
 *
 * <p>HELD ─确认→ CONFIRMED ─入住→ IN_USE ─退房→ CHECKED_OUT ─结算→ SETTLED
 * <br>HELD ─超时→ RELEASED　　CONFIRMED ─取消/爽约→ CANCELLED
 *
 * <p>加时不是状态：前台单机没有审批环节，加时是 IN_USE 上的一个动作（见内核 extend）。
 */
public final class BookingStateMachine {

    private static final Map<String, Set<String>> LEGAL = Map.of(
            Booking.HELD, Set.of(Booking.CONFIRMED, Booking.CANCELLED, Booking.RELEASED),
            Booking.CONFIRMED, Set.of(Booking.IN_USE, Booking.CANCELLED),
            Booking.IN_USE, Set.of(Booking.CHECKED_OUT),
            Booking.CHECKED_OUT, Set.of(Booking.SETTLED),
            Booking.SETTLED, Set.of(),
            Booking.CANCELLED, Set.of(),
            Booking.RELEASED, Set.of());

    private BookingStateMachine() {
    }

    public static void assertCan(String from, String to) {
        Set<String> allowed = LEGAL.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BizException(ErrorCode.BOOKING_ILLEGAL_STATE,
                    String.format("不允许 %s → %s%s", from, to,
                            allowed == null || allowed.isEmpty() ? "（已是终态）"
                                    : "，可前往：" + String.join("/", allowed)));
        }
    }

    public static boolean can(String from, String to) {
        Set<String> allowed = LEGAL.get(from);
        return allowed != null && allowed.contains(to);
    }
}
