package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.entity.IdempotencyKeyEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, Long> {

    Optional<IdempotencyKeyEntity> findByCustomerIdAndOperationAndIdempotencyKey(
            String customerId,
            String operation,
            String idempotencyKey
    );
}
