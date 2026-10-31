package com.limiteddrop.reservation.api.dto;

import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.entity.HoldEntity;
import java.time.Instant;

public record HoldResponse(
        String id,
        Long dropId,
        String customerId,
        int quantity,
        HoldStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static HoldResponse from(HoldEntity entity) {
        return new HoldResponse(
                entity.getId(),
                entity.getDropId(),
                entity.getCustomerId(),
                entity.getQuantity(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
