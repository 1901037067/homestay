package com.school.bnb.vo;

import com.school.bnb.entity.SysUser;

/** 账号视图。<b>永远不带 password_hash</b> —— 哈希进了 JSON 就是离线爆破的原料。 */
public record UserVO(
        Long id,
        String username,
        String displayName,
        Long propertyId,
        String propertyName,
        boolean manager,
        Boolean enabled) {

    public static UserVO of(SysUser u, String propertyName) {
        return new UserVO(u.getId(), u.getUsername(), u.getDisplayName(), u.getPropertyId(),
                propertyName, u.getPropertyId() == null, u.getEnabled());
    }
}
