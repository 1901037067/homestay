package com.school.bnb.service;

import com.school.bnb.common.BizException;
import com.school.bnb.common.ErrorCode;
import com.school.bnb.common.LoginInterceptor;
import com.school.bnb.entity.SysUser;
import com.school.bnb.mapper.SysUserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    public LoginInterceptor.LoginUser login(String username, String rawPassword) {
        SysUser user = sysUserMapper.findByUsername(username);
        if (user == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            // 不区分"用户不存在"和"密码错"，避免枚举账号
            throw new BizException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BizException(ErrorCode.USER_DISABLED, "账号已停用");
        }
        return new LoginInterceptor.LoginUser(user.getId(), user.getUsername(), user.getDisplayName());
    }

    /** 给种子脚本用：与登录校验同一个编码器，哈希必然互认。 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}
