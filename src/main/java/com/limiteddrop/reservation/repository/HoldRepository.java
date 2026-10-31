package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.entity.HoldEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<HoldEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM HoldEntity h WHERE h.id = :id")
    Optional<HoldEntity> findByIdForUpdate(@Param("id") String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE holds
                    SET status = 'CONFIRMED',
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = :holdId
                      AND status = 'ACTIVE'
                      AND expires_at > CURRENT_TIMESTAMP(6)
                    """,
            nativeQuery = true
    )
    int confirmActiveHold(@Param("holdId") String holdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE holds
                    SET status = 'CANCELLED',
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = :holdId
                      AND status = 'ACTIVE'
                    """,
            nativeQuery = true
    )
    int cancelActiveHold(@Param("holdId") String holdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE holds
                    SET status = 'EXPIRED',
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = :holdId
                      AND status = 'ACTIVE'
                      AND expires_at <= CURRENT_TIMESTAMP(6)
                    """,
            nativeQuery = true
    )
    int expireActiveHold(@Param("holdId") String holdId);

    @Query("SELECT h FROM HoldEntity h WHERE h.status = com.limiteddrop.reservation.domain.HoldStatus.ACTIVE AND h.expiresAt <= :now")
    java.util.List<HoldEntity> findExpiredActiveHolds(@Param("now") Instant now);
}
