package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 账号新建/修改。
 *
 * <p>{@code password} 只在新建时必填；修改时留空表示"不动密码" —— 店长改个显示名
 * 不该被迫重输一次密码，而"传空串就当清空"更是会把账号锁死的经典写法。
 *
 * <p>{@code propertyId} 留空 = 店长（看全部门店、能进这一页）；填了 = 该民宿的前台。
 * 没有单独的 role 字段，角色由这一列派生（见 V8 迁移注释）。
 */
public record UserReq(
        Long id,
        @NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
        @NotBlank(message = "显示名不能为空") @Size(max = 64) String displayName,
        String password,
        Long propertyId,
        Boolean enabled) {
}
