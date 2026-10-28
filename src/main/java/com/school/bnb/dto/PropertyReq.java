package com.school.bnb.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalTime;

public record PropertyReq(
        Long id,
        @NotBlank(message = "不能为空") String name,
        String address,
        LocalTime dayCutTime,
        LocalTime openTime,
        LocalTime closeTime) {
}
