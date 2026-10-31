package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.config.AppProperties;
import com.limiteddrop.reservation.domain.HoldEventType;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.entity.HoldEntity;
import com.limiteddrop.reservation.metrics.ReservationMetrics;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.repository.HoldRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpirationService {

    private static final Logger log = LoggerFactory.getLogger(ExpirationService.class);

    private final HoldRepository holdRepository;
    private final DropRepository dropRepository;
    private final OutboxService outboxService;
    private final DropQueryService dropQueryService;
    private final AppProperties appProperties;
    private final ReservationMetrics metrics;

    @PersistenceContext
    private EntityManager entityManager;

    public ExpirationService(
            HoldRepository holdRepository,
            DropRepository dropRepository,
            OutboxService outboxService,
            DropQueryService dropQueryService,
            AppProperties appProperties,
            ReservationMetrics metrics
    ) {
        this.holdRepository = holdRepository;
        this.dropRepository = dropRepository;
        this.outboxService = outboxService;
        this.dropQueryService = dropQueryService;
        this.appProperties = appProperties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.expiration.fixed-delay-ms:5000}")
    @Transactional
    public void processExpiredHolds() {
        int processed = processBatch(appProperties.getExpiration().getBatchSize());
        metrics.recordExpiration(processed);
    }

    @Transactional
    public int processBatch(int limit) {
        List<String> holdIds = findExpiredHoldIds(limit);
        int count = 0;
        for (String holdId : holdIds) {
            if (expireOne(holdId)) {
                count++;
            }
        }
        if (count > 0) {
            log.info("Expired {} holds", count);
        }
        return count;
    }

    private boolean expireOne(String holdId) {
        HoldEntity hold = holdRepository.findByIdForUpdate(holdId).orElse(null);
        if (hold == null || hold.getStatus() != HoldStatus.ACTIVE) {
            return false;
        }
        if (hold.getExpiresAt().isAfter(Instant.now())) {
            return false;
        }
        int updated = holdRepository.expireActiveHold(holdId);
        if (updated != 1) {
            return false;
        }
        dropRepository.releaseInventory(hold.getDropId(), hold.getQuantity());
        outboxService.appendHoldEvent(
                HoldEventType.HOLD_EXPIRED,
                hold.getId(),
                hold.getDropId(),
                hold.getCustomerId(),
                hold.getQuantity()
        );
        dropQueryService.invalidateDrop(hold.getDropId());
        return true;
    }

    /**
     * MySQL: {@code FOR UPDATE SKIP LOCKED}. H2 / others: simple select (no SKIP LOCKED).
     */
    @SuppressWarnings("unchecked")
    List<String> findExpiredHoldIds(int limit) {
        if (isMysql()) {
            return entityManager.createNativeQuery(
                            """
                            SELECT id FROM holds
                            WHERE status = 'ACTIVE' AND expires_at <= CURRENT_TIMESTAMP(6)
                            ORDER BY expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """
                    )
                    .setParameter("limit", limit)
                    .getResultList();
        }

        List<HoldEntity> expired = holdRepository.findExpiredActiveHolds(Instant.now());
        List<String> ids = new ArrayList<>();
        for (HoldEntity hold : expired) {
            ids.add(hold.getId());
            if (ids.size() >= limit) {
                break;
            }
        }
        return ids;
    }

    private boolean isMysql() {
        try {
            String url = (String) entityManager.getEntityManagerFactory()
                    .getProperties()
                    .getOrDefault("hibernate.connection.url", "");
            if (url != null && url.contains("mysql")) {
                return true;
            }
            Object dialect = entityManager.getEntityManagerFactory()
                    .getProperties()
                    .get("hibernate.dialect");
            return dialect != null && dialect.toString().toLowerCase().contains("mysql");
        } catch (Exception ex) {
            return false;
        }
    }
}
