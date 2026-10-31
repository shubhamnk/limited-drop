package com.limiteddrop.reservation.api.dto;

import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.entity.DropEntity;
import java.time.Instant;

public record DropResponse(
        Long id,
        String name,
        String description,
        int totalUnits,
        int availableUnits,
        int reservedUnits,
        int soldUnits,
        DropStatus status,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static DropResponse from(DropEntity entity) {
        return new DropResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getTotalUnits(),
                entity.getAvailableUnits(),
                entity.getReservedUnits(),
                entity.getSoldUnits(),
                entity.getStatus(),
                entity.getStartsAt(),
                entity.getEndsAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
