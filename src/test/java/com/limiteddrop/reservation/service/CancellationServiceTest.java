package com.limiteddrop.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.domain.HoldEventType;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.entity.HoldEntity;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.metrics.ReservationMetrics;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.repository.HoldRepository;
import com.limiteddrop.reservation.repository.IdempotencyKeyRepository;
import com.limiteddrop.reservation.security.CustomerPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

    @Mock HoldRepository holdRepository;
    @Mock DropRepository dropRepository;
    @Mock IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock OutboxService outboxService;
    @Mock DropQueryService dropQueryService;
    @Mock ReservationMetrics metrics;
    @Mock TransactionTemplate transactionTemplate;

    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    IdempotencyService idempotencyService;
    CancellationService cancellationService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(idempotencyKeyRepository, objectMapper);
        cancellationService = new CancellationService(
                holdRepository,
                dropRepository,
                idempotencyService,
                outboxService,
                dropQueryService,
                new DeadlockRetryHelper(),
                metrics,
                transactionTemplate
        );
        authenticate("cust-1");
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });
        when(idempotencyKeyRepository.findByCustomerIdAndOperationAndIdempotencyKey(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancelActiveHold() {
        HoldEntity hold = hold("hold-1", HoldStatus.ACTIVE, Instant.now().plusSeconds(90));
        when(holdRepository.findByIdForUpdate("hold-1")).thenReturn(Optional.of(hold));
        when(holdRepository.cancelActiveHold("hold-1")).thenReturn(1);
        when(dropRepository.releaseInventory(10L, 1)).thenReturn(1);

        HoldResponse response = cancellationService.cancelHold("hold-1", "idem-1");

        assertThat(response.status()).isEqualTo(HoldStatus.CANCELLED);
        verify(outboxService).appendHoldEvent(eq(HoldEventType.HOLD_CANCELLED), eq("hold-1"), eq(10L), eq("cust-1"), eq(1));
        verify(metrics).recordCancellationSuccess();
    }

    @Test
    void cancelAlreadyCancelledNoDoubleRelease() {
        HoldEntity hold = hold("hold-2", HoldStatus.CANCELLED, Instant.now().plusSeconds(90));
        when(holdRepository.findByIdForUpdate("hold-2")).thenReturn(Optional.of(hold));

        HoldResponse response = cancellationService.cancelHold("hold-2", "idem-2");

        assertThat(response.status()).isEqualTo(HoldStatus.CANCELLED);
        verify(dropRepository, never()).releaseInventory(anyLong(), anyInt());
        verify(holdRepository, never()).cancelActiveHold(anyString());
    }

    @Test
    void cancelConfirmedRejected() {
        HoldEntity hold = hold("hold-3", HoldStatus.CONFIRMED, Instant.now().plusSeconds(90));
        when(holdRepository.findByIdForUpdate("hold-3")).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> cancellationService.cancelHold("hold-3", "idem-3"))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("HOLD_CONFIRMED");
    }

    private static void authenticate(String customerId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CustomerPrincipal(customerId), null, List.of())
        );
    }

    private static HoldEntity hold(String id, HoldStatus status, Instant expiresAt) {
        HoldEntity entity = new HoldEntity();
        entity.setId(id);
        entity.setDropId(10L);
        entity.setCustomerId("cust-1");
        entity.setQuantity(1);
        entity.setStatus(status);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
