package com.school.bnb.common;

/** 当前登录人，由拦截器写入、请求结束时清理。 */
public final class AuthContext {

    private static final ThreadLocal<LoginInterceptor.LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    static void set(LoginInterceptor.LoginUser user) {
        HOLDER.set(user);
    }

    static void clear() {
        HOLDER.remove();
    }

    public static LoginInterceptor.LoginUser current() {
        return HOLDER.get();
    }

    public static Long currentUserId() {
        LoginInterceptor.LoginUser u = HOLDER.get();
        return u == null ? null : u.id();
    }
}
