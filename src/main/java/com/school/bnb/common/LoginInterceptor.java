package com.school.bnb.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 单机前台鉴权：HttpSession + 拦截器，不用 JWT（实施计划 1.1）。
 * 登出就是 session.invalidate()，语义自洽。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "loginUser";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        HttpSession session = req.getSession(false);
        LoginUser user = session == null ? null : (LoginUser) session.getAttribute(SESSION_KEY);
        if (user == null) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        AuthContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }

    public record LoginUser(Long id, String username, String displayName) {
    }
}
