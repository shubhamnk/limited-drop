package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.domain.HoldEventType;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.domain.IdempotencyOperation;
import com.limiteddrop.reservation.entity.HoldEntity;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.exception.NotFoundException;
import com.limiteddrop.reservation.metrics.ReservationMetrics;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.repository.HoldRepository;
import com.limiteddrop.reservation.security.CustomerContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CancellationService {

    private final HoldRepository holdRepository;
    private final DropRepository dropRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final DropQueryService dropQueryService;
    private final DeadlockRetryHelper deadlockRetryHelper;
    private final ReservationMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public CancellationService(
            HoldRepository holdRepository,
            DropRepository dropRepository,
            IdempotencyService idempotencyService,
            OutboxService outboxService,
            DropQueryService dropQueryService,
            DeadlockRetryHelper deadlockRetryHelper,
            ReservationMetrics metrics,
            TransactionTemplate transactionTemplate
    ) {
        this.holdRepository = holdRepository;
        this.dropRepository = dropRepository;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.dropQueryService = dropQueryService;
        this.deadlockRetryHelper = deadlockRetryHelper;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
    }

    public HoldResponse cancelHold(String holdId, String idempotencyKey) {
        String customerId = CustomerContext.requireCustomerId();
        HoldService.requireIdempotencyKey(idempotencyKey);

        String requestHash = idempotencyService.hashRequest(Map.of("holdId", holdId));
        Optional<IdempotencyService.StoredResponse> existing = idempotencyService.begin(
                customerId, IdempotencyOperation.CANCEL_HOLD, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return idempotencyService.readStored(existing.get(), HoldResponse.class);
        }

        CancelResult result = deadlockRetryHelper.execute(
                () -> transactionTemplate.execute(status -> doCancel(holdId, customerId))
        );
        if (result.conflict() != null) {
            throw result.conflict();
        }
        HoldResponse response = result.response();
        idempotencyService.saveSuccess(
                customerId,
                IdempotencyOperation.CANCEL_HOLD,
                idempotencyKey,
                requestHash,
                response,
                200
        );
        metrics.recordCancellationSuccess();
        return response;
    }

    private CancelResult doCancel(String holdId, String customerId) {
        HoldEntity hold = holdRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new NotFoundException("HOLD_NOT_FOUND", "Hold not found: " + holdId));
        HoldService.assertOwnership(hold, customerId);

        if (hold.getStatus() == HoldStatus.CANCELLED) {
            // Already cancelled — return current state without releasing inventory again.
            return CancelResult.ok(HoldResponse.from(hold));
        }
        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            throw new ConflictException("HOLD_CONFIRMED", "Cannot cancel a confirmed hold");
        }
        if (hold.getStatus() == HoldStatus.EXPIRED) {
            throw new ConflictException("HOLD_EXPIRED", "Cannot cancel an expired hold");
        }

        Instant now = Instant.now();
        // Expire path must commit before 409 (same rule as confirm).
        int expired = holdRepository.expireActiveHold(holdId);
        if (expired == 1) {
            dropRepository.releaseInventory(hold.getDropId(), hold.getQuantity());
            outboxService.appendHoldEvent(
                    HoldEventType.HOLD_EXPIRED,
                    hold.getId(),
                    hold.getDropId(),
                    hold.getCustomerId(),
                    hold.getQuantity()
            );
            dropQueryService.invalidateDrop(hold.getDropId());
            return CancelResult.conflict(
                    new ConflictException("HOLD_EXPIRED", "Hold has expired and inventory was released")
            );
        }

        int updated = holdRepository.cancelActiveHold(holdId);
        if (updated != 1) {
            throw new ConflictException("HOLD_NOT_ACTIVE", "Hold is no longer active");
        }
        int released = dropRepository.releaseInventory(hold.getDropId(), hold.getQuantity());
        if (released != 1) {
            throw new ConflictException("INVENTORY_RELEASE_FAILED", "Unable to release reserved inventory");
        }

        outboxService.appendHoldEvent(
                HoldEventType.HOLD_CANCELLED,
                hold.getId(),
                hold.getDropId(),
                hold.getCustomerId(),
                hold.getQuantity()
        );
        dropQueryService.invalidateDrop(hold.getDropId());

        hold.setStatus(HoldStatus.CANCELLED);
        hold.setUpdatedAt(now);
        return CancelResult.ok(HoldResponse.from(hold));
    }

    private record CancelResult(HoldResponse response, ConflictException conflict) {
        static CancelResult ok(HoldResponse response) {
            return new CancelResult(response, null);
        }

        static CancelResult conflict(ConflictException conflict) {
            return new CancelResult(null, conflict);
        }
    }
}
