package com.school.bnb.controller;

import com.school.bnb.common.AuthContext;
import com.school.bnb.common.LoginInterceptor;
import com.school.bnb.common.R;
import com.school.bnb.dto.ChangePasswordReq;
import com.school.bnb.dto.LoginReq;
import com.school.bnb.service.AuthService;
import com.school.bnb.service.UserService;
import com.school.bnb.vo.MeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "单机前台登录，HttpSession 实现")
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<MeVO> login(@Valid @RequestBody LoginReq req, HttpServletRequest httpReq) {
        LoginInterceptor.LoginUser user = authService.login(req.username(), req.password());
        HttpSession session = httpReq.getSession(true);
        // 会话固定防护：登录成功必须换一个 session id。否则攻击者预先塞给受害者的 JSESSIONID，
        // 在受害者登录后就变成一个已授权的会话 —— 换 id 而不是新建会话，已写的属性不受影响
        httpReq.changeSessionId();
        session.setAttribute(LoginInterceptor.SESSION_KEY, user);
        return R.ok(authService.toMe(user));
    }

    @Operation(summary = "登出", description = "session.invalidate()，真失效")
    @GetMapping("/logout")
    public R<Void> logout(HttpServletRequest httpReq) {
        HttpSession session = httpReq.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return R.ok();
    }

    @Operation(summary = "当前登录人", description = "含归属民宿与是否店长，界面据此决定可见入口")
    @GetMapping("/me")
    public R<MeVO> me() {
        return R.ok(authService.toMe(AuthContext.current()));
    }

    @Operation(summary = "改自己的密码",
            description = "任何登录账号都能改自己的，需验旧密码；店长重置别人的走 PUT /api/users/{id}")
    @PostMapping("/me/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordReq req) {
        userService.changeMyPassword(req);
        return R.ok();
    }
}
