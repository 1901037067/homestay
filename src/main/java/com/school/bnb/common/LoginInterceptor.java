package com.school.bnb.common;

import com.school.bnb.entity.SysUser;
import com.school.bnb.mapper.SysUserMapper;
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

    private final SysUserMapper userMapper;

    public LoginInterceptor(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        HttpSession session = req.getSession(false);
        LoginUser cached = session == null ? null : (LoginUser) session.getAttribute(SESSION_KEY);
        if (cached == null) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 每个请求回读一次账号，而不是信任 session 里那份快照。两个洞都在这里堵掉：
        //   ① 店长把某人停用后，他那台浏览器还能继续操作到 session 过期为止；
        //   ② 改了某人归属的民宿，他要退出重登才生效 —— 而"改完归属他还是能看原店"
        //      这种表现，测试和答辩都会当成隔离没生效。
        // 代价是一次主键查询。单机前台的量级下，这个代价比"权限改了不生效"便宜得多。
        SysUser fresh = userMapper.selectById(cached.id());
        if (fresh == null || !Boolean.TRUE.equals(fresh.getEnabled())) {
            session.invalidate();
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        LoginUser user = new LoginUser(fresh.getId(), fresh.getUsername(),
                fresh.getDisplayName(), fresh.getPropertyId());
        session.setAttribute(SESSION_KEY, user);
        AuthContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }

    /**
     * @param propertyId 可见范围：null = 店长（全部门店），非空 = 只看这一家。
     *                   角色不单独存，一律由这一列派生（见 V8 迁移注释）。
     */
    public record LoginUser(Long id, String username, String displayName, Long propertyId) {

        public boolean global() {
            return propertyId == null;
        }
    }
}
