package com.school.bnb.booking;

import com.school.bnb.common.ErrorCode;
import com.school.bnb.entity.Room;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 可售性规则的唯一出处。
 *
 * <p>抽这一个类的全部理由来自一轮审阅：下单入口、可售房间查询、房态日历格子三处各自实现了一遍
 * "这段时间能不能卖"，实现还不完全一样 —— 于是出现过"查询说可售、下单却被拒"的矛盾。
 * 规则只写一次，三个调用方都从 {@link #check} 走。
 *
 * <p>注意它<b>不管时间冲突</b>。冲突判定必须留在内核里、在房间行锁之后用当前读做 ——
 * 这里做的是"策略"，那里做的是"并发正确性"，混在一起会两头都做坏。
 */
public final class SellPolicy {

    /** 一次判定需要的全部输入，由调用方从房间/房型/民宿三张表取出后传进来。 */
    public record Rule(
            String roomStatus,
            String roomNo,
            LocalTime openTime,
            LocalTime closeTime,
            int minHours,
            LocalTime fullDayStart,
            LocalTime fullDayEnd) {
    }

    /** 判定结果。code 跟着原因走，前端才能区别处理（下架 vs 时长不足）。 */
    public record Verdict(Integer code, String reason) {

        public static final Verdict OK = new Verdict(null, null);

        public boolean sellable() {
            return code == null;
        }
    }

    private SellPolicy() {
    }

    public static Verdict check(Rule r, LocalDateTime start, LocalDateTime end, SaleType type) {
        if (start == null || end == null || !end.isAfter(start)) {
            return fail(ErrorCode.INVALID_TIME_RANGE, "结束时间必须晚于开始时间（半开区间 [start, end)）");
        }
        if (!Room.ON_SALE.equals(r.roomStatus())) {
            return fail(ErrorCode.ROOM_NOT_ON_SALE,
                    "房间 " + r.roomNo() + " 当前状态 " + r.roomStatus() + "，不可售");
        }
        // 可售窗口约束的是入住时刻。全日房模板 14:00 入住天然满足，所以不需要"豁免"分支。
        if (start.toLocalTime().isBefore(r.openTime()) || start.toLocalTime().isAfter(r.closeTime())) {
            return fail(ErrorCode.ROOM_NOT_ON_SALE, "入住时刻 " + start.toLocalTime()
                    + " 不在可售窗口 " + r.openTime() + "–" + r.closeTime() + " 内");
        }
        if (type == SaleType.FULL_DAY) {
            return fullDayCheck(r, start, end);
        }

        // 钟点：必须当天开始当天结束，两端都落在窗口里，且不少于起步时长
        if (!start.toLocalDate().equals(end.toLocalDate())) {
            return fail(ErrorCode.INVALID_TIME_RANGE, "钟点预订不能跨天，如需跨天请按全日房下单");
        }
        if (end.toLocalTime().isAfter(r.closeTime())) {
            return fail(ErrorCode.ROOM_NOT_ON_SALE,
                    "结束时刻 " + end.toLocalTime() + " 超出可售窗口 " + r.closeTime());
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < r.minHours() * 60L) {
            return fail(ErrorCode.INVALID_TIME_RANGE,
                    "该房型起步 " + r.minHours() + " 小时，当前只订了 " + minutes + " 分钟");
        }
        return Verdict.OK;
    }

    public static boolean isSellable(Rule r, LocalDateTime start, LocalDateTime end, SaleType type) {
        return check(r, start, end, type).sellable();
    }

    /**
     * 加时是<b>另一套规则</b>，不能复用 {@link #check}。
     *
     * <p>原因：全日房的 check 要求区间<b>严格等于</b>房型模板，于是"把 12:00 退房延到 14:00"
     * 会被当成"你没按模板下单"拒掉 —— 加时恰恰就是要偏离模板。
     * 而钟点的"不许跨天""不少于起步时长"对加时也没意义（它只会变长）。
     *
     * <p>加时只需要两件事：必须变长，且当天结束的话不能推出可售窗口。
     */
    public static Verdict checkExtension(Rule r, LocalDateTime start,
                                         LocalDateTime currentEnd, LocalDateTime newEnd) {
        if (newEnd == null) {
            return fail(ErrorCode.INVALID_TIME_RANGE, "必须指定加时后的结束时间");
        }
        if (!newEnd.isAfter(currentEnd)) {
            return fail(ErrorCode.INVALID_TIME_RANGE,
                    "加时后的结束时间必须晚于当前结束时间 " + currentEnd);
        }
        if (start.toLocalDate().equals(newEnd.toLocalDate()) && newEnd.toLocalTime().isAfter(r.closeTime())) {
            return fail(ErrorCode.ROOM_NOT_ON_SALE,
                    "加时后的结束时刻 " + newEnd.toLocalTime() + " 超出可售窗口 " + r.closeTime());
        }
        return Verdict.OK;
    }

    /**
     * 全日房模板的区间必须与服务端按房型算出来的完全一致。
     *
     * <p>这条同时干两件事：让"跨天就免起步时长"的漏洞失去意义（跨天的 HOURLY 上面已经拒了），
     * 以及防止前端把全日房改成任意时段来绕开定价。
     */
    private static Verdict fullDayCheck(Rule r, LocalDateTime start, LocalDateTime end) {
        LocalDateTime expectStart = start.toLocalDate().atTime(r.fullDayStart());
        LocalDateTime expectEnd = r.fullDayEnd().isAfter(r.fullDayStart())
                ? start.toLocalDate().atTime(r.fullDayEnd())
                : start.toLocalDate().plusDays(1).atTime(r.fullDayEnd());   // 次日结束
        if (!start.equals(expectStart) || !end.equals(expectEnd)) {
            return fail(ErrorCode.INVALID_TIME_RANGE,
                    "全日房时段固定为 " + expectStart + " → " + expectEnd + "，请勿自行指定区间");
        }
        return Verdict.OK;
    }

    private static Verdict fail(int code, String reason) {
        return new Verdict(code, reason);
    }
}
