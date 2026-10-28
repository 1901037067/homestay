package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginReq(
        @NotBlank(message = "不能为空") String username,
        @NotBlank(message = "不能为空") String password) {
}
