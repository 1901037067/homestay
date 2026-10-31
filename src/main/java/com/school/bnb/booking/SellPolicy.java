package com.school.bnb.booking;

import com.school.bnb.common.ErrorCode;
import com.school.bnb.entity.Room;
import java.time.Duration;
import java.time.LocalDate;
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

    /** 相邻两晚模板起点的间隔。全链路 naive 本地时间、无夏令时，所以恒等于 24 小时。 */
    private static final int DAY_MINUTES = 24 * 60;

    /**
     * 一次登记的连住上限。<b>和前端控件是同一个数</b>，两边各说一套只会让人以为能订 365 晚。
     *
     * <p>为什么是 60 而不是 365，还有一条硬理由：`bill.detail_json` 是 MySQL TEXT（约 64KB），
     * 而多晚账单是一晚一条起步明细。实测一条明细约 200 字节，365 晚约 76KB —— 超过列上限，
     * 严格模式下插入直接失败、非严格模式会截断成读不回来的坏 JSON。60 晚约 12KB，余量充足。
     * 真要住更久，拆单本来也是更好的做法：中途改价、分段结算都更好处理。
     */
    public static final int MAX_FULL_DAY_NIGHTS = 60;

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
     * 全日房必须<b>从某晚的模板起点开始、按整晚连住到第 N 晚的模板终点</b>。
     *
     * <p>这条同时干两件事：让"跨天就免起步时长"的漏洞失去意义（跨天的 HOURLY 上面已经拒了），
     * 以及防止前端把全日房改成任意时段来绕开定价。
     */
    private static Verdict fullDayCheck(Rule r, LocalDateTime start, LocalDateTime end) {
        int nights = fullDayNights(start, end, r.fullDayStart(), r.fullDayEnd());
        if (nights == 0) {
            return fail(ErrorCode.INVALID_TIME_RANGE,
                    "全日房只能整晚连住：从 " + r.fullDayStart() + " 起、到某天 " + r.fullDayEnd()
                            + " 止（订 N 晚就选 N 个整晚），收到 " + start + " → " + end);
        }
        // 上限只加在校验这一侧，不加进 fullDayNights —— 那个方法还被计费调用，
        // 让它对一张已存在的长单返回 0，等于把这张单的晚数静默改成 1
        if (nights > MAX_FULL_DAY_NIGHTS) {
            return fail(ErrorCode.INVALID_TIME_RANGE,
                    "一次最多连住 " + MAX_FULL_DAY_NIGHTS + " 晚，这一单要住 " + nights
                            + " 晚；再长请拆成几张单，中途改价与结算都更好处理");
        }
        return Verdict.OK;
    }

    /**
     * 这个区间是连住几晚；<b>拼不出整数晚就返回 0</b>。
     *
     * <p>晚数的<b>唯一权威判据</b>：下单校验、下单时的计费快照、结算时的试算都调它。
     * 两处各算一遍，就会出现"校验放行 A、计费按 B 收钱"。
     *
     * <p>N 晚的总长 = 24 小时 ×(N-1) + 一晚的模板时长。相邻两晚的起点恒差 24 小时
     * （全链路 naive 本地时间，无夏令时）。中间那些 {@code fullDayEnd → fullDayStart}
     * 的白天空档<b>算客人留着房</b>，所以整段是一条连续占用行，防超卖口径不用改。
     *
     * <p>模板本身比一天还长（晚与晚自相重叠）时这里除不尽，直接判 0 —— 那种配置下
     * "连住"没有意义，不该假装支持。
     */
    public static int fullDayNights(LocalDateTime start, LocalDateTime end,
                                    LocalTime fullDayStart, LocalTime fullDayEnd) {
        LocalDateTime[] first = fullDayRange(start.toLocalDate(), fullDayStart, fullDayEnd);
        if (!start.equals(first[0])) {
            return 0;
        }
        int nightMinutes = (int) Duration.between(first[0], first[1]).toMinutes();
        long rest = Duration.between(start, end).toMinutes() - nightMinutes;
        if (rest < 0 || rest % DAY_MINUTES != 0) {
            return 0;
        }
        int nights = (int) (rest / DAY_MINUTES) + 1;
        LocalDateTime[] last =
                fullDayRange(start.toLocalDate().plusDays(nights - 1L), fullDayStart, fullDayEnd);
        return end.equals(last[1]) ? nights : 0;
    }

    /**
     * 房型全日房模板在某个入住日期上的绝对区间。
     *
     * <p>提成公开方法是因为<b>计费也要用它</b>：全日房的"起步价覆盖时长"就是这个模板的时长
     * （14:00→次日 12:00 即 22 小时），加时的部分才按超时单价收。两处各写一遍
     * "end 小于 start 就算次日"，迟早一边改了另一边没改，于是账单和下单校验互相矛盾。
     */
    public static LocalDateTime[] fullDayRange(LocalDate date, LocalTime start, LocalTime end) {
        LocalDateTime s = date.atTime(start);
        LocalDateTime e = end.isAfter(start) ? date.atTime(end) : date.plusDays(1).atTime(end);
        return new LocalDateTime[]{s, e};
    }

    private static Verdict fail(int code, String reason) {
        return new Verdict(code, reason);
    }
}
