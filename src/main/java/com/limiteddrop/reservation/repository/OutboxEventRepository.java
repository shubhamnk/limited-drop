package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    default List<OutboxEventEntity> findPending(int limit) {
        return findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, Pageable.ofSize(limit));
    }

    long countByStatus(OutboxStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE OutboxEventEntity e
            SET e.status = com.limiteddrop.reservation.domain.OutboxStatus.PUBLISHED,
                e.publishedAt = :publishedAt
            WHERE e.id = :id AND e.status = com.limiteddrop.reservation.domain.OutboxStatus.PENDING
            """
    )
    int markPublished(@Param("id") Long id, @Param("publishedAt") Instant publishedAt);
}
