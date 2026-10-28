package com.school.bnb.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record ExtendReq(@NotNull LocalDateTime newEndTime) {
}
