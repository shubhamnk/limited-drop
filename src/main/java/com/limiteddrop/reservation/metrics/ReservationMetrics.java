package com.limiteddrop.reservation.metrics;

import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;

    private Counter holdCreationSuccess;
    private Counter holdCreationFailure;
    private Counter confirmationSuccess;
    private Counter confirmationFailure;
    private Counter cancellationSuccess;
    private Counter expirationProcessed;

    public ReservationMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.meterRegistry = meterRegistry;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostConstruct
    void init() {
        holdCreationSuccess = Counter.builder("hold_creation_success").register(meterRegistry);
        holdCreationFailure = Counter.builder("hold_creation_failure").register(meterRegistry);
        confirmationSuccess = Counter.builder("hold_confirmation_success").register(meterRegistry);
        confirmationFailure = Counter.builder("hold_confirmation_failure").register(meterRegistry);
        cancellationSuccess = Counter.builder("hold_cancellation_success").register(meterRegistry);
        expirationProcessed = Counter.builder("hold_expiration_processed").register(meterRegistry);
        Gauge.builder("outbox_pending", outboxEventRepository, repo -> repo.countByStatus(OutboxStatus.PENDING))
                .register(meterRegistry);
    }

    public void recordHoldCreationSuccess() {
        holdCreationSuccess.increment();
    }

    public void recordHoldCreationFailure() {
        holdCreationFailure.increment();
    }

    public void recordConfirmationSuccess() {
        confirmationSuccess.increment();
    }

    public void recordConfirmationFailure() {
        confirmationFailure.increment();
    }

    public void recordCancellationSuccess() {
        cancellationSuccess.increment();
    }

    public void recordExpiration(int count) {
        if (count > 0) {
            expirationProcessed.increment(count);
        }
    }
}
