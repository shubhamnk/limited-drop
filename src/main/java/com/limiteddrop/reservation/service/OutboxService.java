package com.limiteddrop.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.domain.HoldEventType;
import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.entity.OutboxEventEntity;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Append an outbox event in the caller's transaction.
     */
    @Transactional
    public OutboxEventEntity appendHoldEvent(
            HoldEventType eventType,
            String holdId,
            Long dropId,
            String customerId,
            int quantity
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdId", holdId);
        payload.put("dropId", dropId);
        payload.put("customerId", customerId);
        payload.put("quantity", quantity);
        payload.put("eventType", eventType.name());
        payload.put("occurredAt", Instant.now().toString());

        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateId(holdId);
        event.setEventType(eventType.name());
        event.setPayload(writeJson(payload));
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setCreatedAt(Instant.now());
        return outboxEventRepository.save(event);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize outbox payload", e);
        }
    }
}
