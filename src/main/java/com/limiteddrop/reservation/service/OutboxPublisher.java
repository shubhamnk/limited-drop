package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.config.AppProperties;
import com.limiteddrop.reservation.config.RabbitConfig;
import com.limiteddrop.reservation.domain.HoldEventType;
import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.entity.OutboxEventEntity;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes pending outbox rows to RabbitMQ. Tolerates broker failures by leaving
 * rows PENDING (or bumping retry_count) for the next scheduled tick.
 */
@Component
@ConditionalOnBean(RabbitTemplate.class)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            RabbitTemplate rabbitTemplate,
            AppProperties appProperties
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:2000}")
    public void publishPending() {
        List<OutboxEventEntity> pending = outboxEventRepository.findPending(appProperties.getOutbox().getBatchSize());
        for (OutboxEventEntity event : pending) {
            try {
                publishOne(event);
            } catch (Exception ex) {
                log.warn("Failed to publish outbox event {}: {}", event.getEventId(), ex.getMessage());
                bumpRetry(event);
            }
        }
    }

    @Transactional
    protected void publishOne(OutboxEventEntity event) throws Exception {
        String routingKey = resolveRoutingKey(event.getEventType());
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                routingKey,
                event.getPayload().getBytes(StandardCharsets.UTF_8),
                message -> {
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setHeader("eventType", event.getEventType());
                    message.getMessageProperties().setHeader("aggregateId", event.getAggregateId());
                    return message;
                },
                correlationData
        );
        CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        if (confirm == null || !confirm.isAck()) {
            throw new IllegalStateException("Publisher confirm nack for event " + event.getEventId());
        }
        outboxEventRepository.markPublished(event.getId(), Instant.now());
    }

    @Transactional
    protected void bumpRetry(OutboxEventEntity event) {
        OutboxEventEntity managed = outboxEventRepository.findById(event.getId()).orElse(null);
        if (managed == null || managed.getStatus() != OutboxStatus.PENDING) {
            return;
        }
        managed.setRetryCount(managed.getRetryCount() + 1);
        if (managed.getRetryCount() >= 25) {
            managed.setStatus(OutboxStatus.FAILED);
        }
        outboxEventRepository.save(managed);
    }

    private static String resolveRoutingKey(String eventType) {
        try {
            return HoldEventType.valueOf(eventType).routingKey();
        } catch (IllegalArgumentException ex) {
            return "drop.hold.unknown";
        }
    }
}
