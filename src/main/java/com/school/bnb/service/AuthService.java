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
    private final PropertyService propertyService;
    private final OperationLogService opLog;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(SysUserMapper sysUserMapper, PropertyService propertyService,
                       OperationLogService opLog) {
        this.sysUserMapper = sysUserMapper;
        this.propertyService = propertyService;
        this.opLog = opLog;
    }

    public LoginInterceptor.LoginUser login(String username, String rawPassword) {
        SysUser user = sysUserMapper.findByUsername(username);
        if (user == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            // 三种失败在审计页要分得开（撞库 / 记错密码 / 拿停用号在试），
            // 但对外一律"用户名或密码错误"，不在接口上泄露账号是否存在
            opLog.recordAuth("LOGIN_FAILED", user == null ? null : user.getId(), username,
                    user == null ? "登录失败：账号不存在" : "登录失败：密码错误");
            throw new BizException(ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            opLog.recordAuth("LOGIN_FAILED", user.getId(), username, "登录失败：账号已停用");
            throw new BizException(ErrorCode.USER_DISABLED, "账号已停用");
        }
        // 注意不能在登录前从 AuthContext 取操作人：这里还是空的，recordAuth 显式带用户名
        opLog.recordAuth("LOGIN_SUCCESS", user.getId(), username,
                "登录成功" + (user.getPropertyId() == null ? "（店长）" : "（前台）"));
        return new LoginInterceptor.LoginUser(user.getId(), user.getUsername(),
                user.getDisplayName(), user.getPropertyId());
    }

    /** 给种子脚本用：与登录校验同一个编码器，哈希必然互认。 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 登录人 + 他所属的民宿名。绑定的民宿被改名时这里跟着变，不存副本。 */
    public com.school.bnb.vo.MeVO toMe(LoginInterceptor.LoginUser u) {
        String name = u.propertyId() == null ? null : propertyService.nameMap().get(u.propertyId());
        return com.school.bnb.vo.MeVO.of(u, name);
    }
}
