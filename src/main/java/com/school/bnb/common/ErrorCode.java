package com.school.bnb.common;

/** 业务错误码。写进论文的错误分类表就用这张。 */
public final class ErrorCode {

    public static final int UNAUTHORIZED = 401;
    public static final int BAD_REQUEST = 400;

    public static final int LOGIN_FAILED = 1001;
    public static final int USER_DISABLED = 1002;
    /** 越界：请求的数据不属于当前账号可看的民宿，或功能只允许店长使用。 */
    public static final int FORBIDDEN = 1003;
    public static final int DUPLICATE_USERNAME = 1004;
    public static final int USER_NOT_FOUND = 1005;

    public static final int ROOM_NOT_FOUND = 2001;
    public static final int ROOM_HAS_HISTORY = 2002;
    public static final int ROOM_NOT_ON_SALE = 2003;

    public static final int PROPERTY_NOT_FOUND = 2010;
    public static final int ROOM_TYPE_NOT_FOUND = 2011;
    public static final int DUPLICATE_KEY = 2012;
    /** 有下级数据（房型/房间/订单）时禁止物理删除，见实施计划 2.6。 */
    public static final int HAS_CHILDREN = 2013;

    public static final int BOOKING_NOT_FOUND = 3001;
    public static final int BOOKING_ILLEGAL_STATE = 3002;

    /** 防超卖核心：同房间时段冲突。 */
    public static final int ROOM_TIME_CONFLICT = 3003;

    public static final int INVALID_TIME_RANGE = 3004;

    /**
     * 同一个 requestId 带上了不同的房间/时段 —— 不能回放旧单，也不能当新单受理。
     * 典型成因：第一次提交其实成功了但响应超时，前台在同一个弹窗里改了时段重试。
     */
    public static final int IDEMPOTENCY_CONFLICT = 3005;

    /** 该房间仍有人在住（含超时未退房），不能开新单。 */
    public static final int ROOM_OCCUPIED_BY_GUEST = 3006;

    public static final int RULE_NOT_FOUND = 2020;
    /** 规则自身的参数矛盾（倍率填 0、只有 validTo、房型不属于这家民宿等）。 */
    public static final int RULE_INVALID = 2021;

    /** 账单：已取消/未入住的订单不产生费用，没什么可试算的。 */
    public static final int NOTHING_TO_BILL = 3007;

    private ErrorCode() {
    }
}
