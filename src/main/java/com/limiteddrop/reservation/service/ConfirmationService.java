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
public class ConfirmationService {

    private final HoldRepository holdRepository;
    private final DropRepository dropRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final DropQueryService dropQueryService;
    private final DeadlockRetryHelper deadlockRetryHelper;
    private final ReservationMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public ConfirmationService(
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

    public HoldResponse confirmHold(String holdId, String idempotencyKey) {
        String customerId = CustomerContext.requireCustomerId();
        HoldService.requireIdempotencyKey(idempotencyKey);

        String requestHash = idempotencyService.hashRequest(Map.of("holdId", holdId));
        Optional<IdempotencyService.StoredResponse> existing = idempotencyService.begin(
                customerId, IdempotencyOperation.CONFIRM_HOLD, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return idempotencyService.readStored(existing.get(), HoldResponse.class);
        }

        try {
            // Commit expire+release before surfacing 409 so the conflict is durable (TDD §5.4).
            ConfirmResult result = deadlockRetryHelper.execute(
                    () -> transactionTemplate.execute(status -> doConfirm(holdId, customerId))
            );
            if (result.conflict() != null) {
                metrics.recordConfirmationFailure();
                throw result.conflict();
            }
            HoldResponse response = result.response();
            idempotencyService.saveSuccess(
                    customerId,
                    IdempotencyOperation.CONFIRM_HOLD,
                    idempotencyKey,
                    requestHash,
                    response,
                    200
            );
            metrics.recordConfirmationSuccess();
            return response;
        } catch (ConflictException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            metrics.recordConfirmationFailure();
            throw ex;
        }
    }

    private ConfirmResult doConfirm(String holdId, String customerId) {
        HoldEntity hold = holdRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new NotFoundException("HOLD_NOT_FOUND", "Hold not found: " + holdId));
        HoldService.assertOwnership(hold, customerId);

        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            return ConfirmResult.ok(HoldResponse.from(hold));
        }
        if (hold.getStatus() == HoldStatus.CANCELLED) {
            throw new ConflictException("HOLD_CANCELLED", "Cannot confirm a cancelled hold");
        }
        if (hold.getStatus() == HoldStatus.EXPIRED) {
            throw new ConflictException("HOLD_EXPIRED", "Cannot confirm an expired hold");
        }

        Instant now = Instant.now();
        // Prefer DB clock via conditional UPDATE; Java check is a fast path before that.
        int updated = holdRepository.confirmActiveHold(holdId);
        if (updated != 1) {
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
                return ConfirmResult.conflict(
                        new ConflictException("HOLD_EXPIRED", "Hold has expired and inventory was released")
                );
            }
            throw new ConflictException("HOLD_NOT_ACTIVE", "Hold is no longer active");
        }
        int confirmed = dropRepository.confirmInventory(hold.getDropId(), hold.getQuantity());
        if (confirmed != 1) {
            throw new ConflictException("INVENTORY_CONFIRM_FAILED", "Unable to confirm reserved inventory");
        }

        outboxService.appendHoldEvent(
                HoldEventType.HOLD_CONFIRMED,
                hold.getId(),
                hold.getDropId(),
                hold.getCustomerId(),
                hold.getQuantity()
        );
        dropQueryService.invalidateDrop(hold.getDropId());

        hold.setStatus(HoldStatus.CONFIRMED);
        hold.setUpdatedAt(now);
        return ConfirmResult.ok(HoldResponse.from(hold));
    }

    private record ConfirmResult(HoldResponse response, ConflictException conflict) {
        static ConfirmResult ok(HoldResponse response) {
            return new ConfirmResult(response, null);
        }

        static ConfirmResult conflict(ConflictException conflict) {
            return new ConfirmResult(null, conflict);
        }
    }
}
