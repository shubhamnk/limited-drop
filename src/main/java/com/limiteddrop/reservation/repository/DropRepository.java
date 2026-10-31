package com.limiteddrop.reservation.repository;

import com.limiteddrop.reservation.entity.DropEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DropRepository extends JpaRepository<DropEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE drops
                    SET available_units = available_units - :qty,
                        reserved_units = reserved_units + :qty,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = :dropId
                      AND status = 'LIVE'
                      AND available_units >= :qty
                      AND starts_at <= CURRENT_TIMESTAMP(6)
                      AND ends_at > CURRENT_TIMESTAMP(6)
                    """,
            nativeQuery = true
    )
    int allocateInventory(@Param("dropId") Long dropId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE drops
                    SET reserved_units = reserved_units - :qty,
                        sold_units = sold_units + :qty,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = :dropId
                      AND reserved_units >= :qty
                    """,
            nativeQuery = true
    )
    int confirmInventory(@Param("dropId") Long dropId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE drops
                    SET available_units = available_units + :qty,
                        reserved_units = reserved_units - :qty,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = :dropId
                      AND reserved_units >= :qty
                    """,
            nativeQuery = true
    )
    int releaseInventory(@Param("dropId") Long dropId, @Param("qty") int qty);
}
