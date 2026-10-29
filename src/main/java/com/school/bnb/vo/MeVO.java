package com.school.bnb.vo;

import com.school.bnb.common.LoginInterceptor.LoginUser;

/**
 * 当前登录人。带 propertyName 与 manager，是为了让界面不必再猜：
 * 侧栏的"哪家店 · 什么角色"、要不要显示「账号」入口，都从这一份派生。
 */
public record MeVO(
        Long id,
        String username,
        String displayName,
        Long propertyId,
        String propertyName,
        boolean manager) {

    public static MeVO of(LoginUser u, String propertyName) {
        return new MeVO(u.id(), u.username(), u.displayName(), u.propertyId(),
                propertyName, u.global());
    }
}
