package com.limiteddrop.reservation.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateHoldRequest(
        @NotNull @Min(1) Integer quantity
) {
}
