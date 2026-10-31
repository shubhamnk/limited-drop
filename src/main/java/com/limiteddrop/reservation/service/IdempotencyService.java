package com.limiteddrop.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.entity.IdempotencyKeyEntity;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.repository.IdempotencyKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency begin/check and response persistence.
 *
 * <p>Retention note: rows in {@code idempotency_keys} should be pruned by a background job
 * after a retention window (e.g. 24–72h). This service does not delete keys; callers rely on
 * the unique (customer_id, operation, idempotency_key) constraint for correctness.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public String hashRequest(Object payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash idempotency payload", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<StoredResponse> begin(
            String customerId,
            String operation,
            String idempotencyKey,
            String requestHash
    ) {
        Optional<IdempotencyKeyEntity> existing = repository.findByCustomerIdAndOperationAndIdempotencyKey(
                customerId, operation, idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyKeyEntity entity = existing.get();
        if (!entity.getRequestHash().equals(requestHash)) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was reused with a different request payload"
            );
        }
        return Optional.of(new StoredResponse(entity.getResponseBody(), entity.getHttpStatus()));
    }

    @Transactional
    public void saveSuccess(
            String customerId,
            String operation,
            String idempotencyKey,
            String requestHash,
            Object responseBody,
            int httpStatus
    ) {
        Optional<IdempotencyKeyEntity> existing = repository.findByCustomerIdAndOperationAndIdempotencyKey(
                customerId, operation, idempotencyKey);
        if (existing.isPresent()) {
            return;
        }
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.setCustomerId(customerId);
        entity.setOperation(operation);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setRequestHash(requestHash);
        entity.setResponseBody(writeJson(responseBody));
        entity.setHttpStatus(httpStatus);
        entity.setCreatedAt(Instant.now());
        repository.save(entity);
    }

    public <T> T readStored(StoredResponse stored, Class<T> type) {
        try {
            return objectMapper.readValue(stored.body().getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deserialize stored idempotent response", e);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize idempotent response", e);
        }
    }

    public record StoredResponse(String body, int httpStatus) {
    }
}
