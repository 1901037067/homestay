package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 改自己的密码。
 *
 * <p>单独一个 DTO 而不是复用 {@code UserReq}：那个是店长编辑别人的账号，字段多得多
 * （归属、启用状态、可选重置密码）。把两件事塞进一个请求体，就会出现
 * "我改自己密码时顺手带了 propertyId" 这种说不清的组合。
 */
public record ChangePasswordReq(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
