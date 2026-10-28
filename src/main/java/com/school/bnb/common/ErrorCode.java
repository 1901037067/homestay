package com.school.bnb.common;

/** 业务错误码。写进论文的错误分类表就用这张。 */
public final class ErrorCode {

    public static final int UNAUTHORIZED = 401;
    public static final int BAD_REQUEST = 400;

    public static final int LOGIN_FAILED = 1001;
    public static final int USER_DISABLED = 1002;

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

    private ErrorCode() {
    }
}
