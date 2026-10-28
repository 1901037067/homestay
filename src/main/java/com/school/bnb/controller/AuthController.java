package com.school.bnb.controller;

import com.school.bnb.common.AuthContext;
import com.school.bnb.common.LoginInterceptor;
import com.school.bnb.common.R;
import com.school.bnb.dto.LoginReq;
import com.school.bnb.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<Map<String, String>> login(@Valid @RequestBody LoginReq req, HttpServletRequest httpReq) {
        LoginInterceptor.LoginUser user = authService.login(req.username(), req.password());
        HttpSession session = httpReq.getSession(true);
        session.setAttribute(LoginInterceptor.SESSION_KEY, user);
        return R.ok(Map.of("username", user.username(), "displayName", user.displayName()));
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

    @Operation(summary = "当前登录人")
    @GetMapping("/me")
    public R<LoginInterceptor.LoginUser> me() {
        return R.ok(AuthContext.current());
    }
}
