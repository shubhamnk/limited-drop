package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.api.dto.CreateHoldRequest;
import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.config.AppProperties;
import com.limiteddrop.reservation.domain.HoldEventType;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.domain.IdempotencyOperation;
import com.limiteddrop.reservation.entity.HoldEntity;
import com.limiteddrop.reservation.exception.BadRequestException;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.exception.ForbiddenException;
import com.limiteddrop.reservation.exception.NotFoundException;
import com.limiteddrop.reservation.metrics.ReservationMetrics;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.repository.HoldRepository;
import com.limiteddrop.reservation.security.CustomerContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final DropRepository dropRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final DropQueryService dropQueryService;
    private final AppProperties appProperties;
    private final DeadlockRetryHelper deadlockRetryHelper;
    private final ReservationMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public HoldService(
            HoldRepository holdRepository,
            DropRepository dropRepository,
            IdempotencyService idempotencyService,
            OutboxService outboxService,
            DropQueryService dropQueryService,
            AppProperties appProperties,
            DeadlockRetryHelper deadlockRetryHelper,
            ReservationMetrics metrics,
            TransactionTemplate transactionTemplate
    ) {
        this.holdRepository = holdRepository;
        this.dropRepository = dropRepository;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.dropQueryService = dropQueryService;
        this.appProperties = appProperties;
        this.deadlockRetryHelper = deadlockRetryHelper;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
    }

    public HoldResponse createHold(Long dropId, CreateHoldRequest request, String idempotencyKey) {
        String customerId = CustomerContext.requireCustomerId();
        validateQuantity(request.quantity());
        requireIdempotencyKey(idempotencyKey);

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("dropId", dropId);
        payload.put("quantity", request.quantity());
        String requestHash = idempotencyService.hashRequest(payload);
        Optional<IdempotencyService.StoredResponse> existing = idempotencyService.begin(
                customerId, IdempotencyOperation.CREATE_HOLD, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return idempotencyService.readStored(existing.get(), HoldResponse.class);
        }

        try {
            HoldResponse response = deadlockRetryHelper.execute(
                    () -> transactionTemplate.execute(status -> doCreateHold(dropId, customerId, request.quantity()))
            );
            idempotencyService.saveSuccess(
                    customerId,
                    IdempotencyOperation.CREATE_HOLD,
                    idempotencyKey,
                    requestHash,
                    response,
                    201
            );
            metrics.recordHoldCreationSuccess();
            return response;
        } catch (RuntimeException ex) {
            metrics.recordHoldCreationFailure();
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(String holdId) {
        String customerId = CustomerContext.requireCustomerId();
        HoldEntity hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new NotFoundException("HOLD_NOT_FOUND", "Hold not found: " + holdId));
        assertOwnership(hold, customerId);
        return HoldResponse.from(hold);
    }

    private HoldResponse doCreateHold(Long dropId, String customerId, int quantity) {
        if (!dropRepository.existsById(dropId)) {
            throw new NotFoundException("DROP_NOT_FOUND", "Drop not found: " + dropId);
        }

        int allocated = dropRepository.allocateInventory(dropId, quantity);
        if (allocated == 0) {
            throw new ConflictException(
                    "INSUFFICIENT_INVENTORY",
                    "Unable to allocate inventory for drop " + dropId
            );
        }

        Instant now = Instant.now();
        HoldEntity hold = new HoldEntity();
        hold.setId(UUID.randomUUID().toString());
        hold.setDropId(dropId);
        hold.setCustomerId(customerId);
        hold.setQuantity(quantity);
        hold.setStatus(HoldStatus.ACTIVE);
        hold.setExpiresAt(now.plusSeconds(appProperties.getHold().getDurationSeconds()));
        hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        holdRepository.save(hold);

        outboxService.appendHoldEvent(
                HoldEventType.HOLD_CREATED,
                hold.getId(),
                dropId,
                customerId,
                quantity
        );
        dropQueryService.invalidateDrop(dropId);
        return HoldResponse.from(hold);
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > appProperties.getHold().getMaxQuantity()) {
            throw new BadRequestException(
                    "INVALID_QUANTITY",
                    "quantity must be between 1 and " + appProperties.getHold().getMaxQuantity()
            );
        }
    }

    static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        }
    }

    static void assertOwnership(HoldEntity hold, String customerId) {
        if (!hold.getCustomerId().equals(customerId)) {
            throw new ForbiddenException("HOLD_FORBIDDEN", "Hold does not belong to the authenticated customer");
        }
    }

}
