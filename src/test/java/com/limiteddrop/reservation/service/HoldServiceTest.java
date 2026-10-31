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
import com.limiteddrop.reservation.api.dto.CreateHoldRequest;
import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.config.AppProperties;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.domain.IdempotencyOperation;
import com.limiteddrop.reservation.entity.HoldEntity;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.exception.ForbiddenException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    @Mock DropRepository dropRepository;
    @Mock HoldRepository holdRepository;
    @Mock IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock OutboxService outboxService;
    @Mock DropQueryService dropQueryService;
    @Mock ReservationMetrics metrics;
    @Mock TransactionTemplate transactionTemplate;

    AppProperties appProperties = new AppProperties();
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    IdempotencyService idempotencyService;
    DeadlockRetryHelper deadlockRetryHelper = new DeadlockRetryHelper();
    HoldService holdService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(idempotencyKeyRepository, objectMapper);
        holdService = new HoldService(
                holdRepository,
                dropRepository,
                idempotencyService,
                outboxService,
                dropQueryService,
                appProperties,
                deadlockRetryHelper,
                metrics,
                transactionTemplate
        );
        authenticate("cust-1");
        org.mockito.Mockito.lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createHoldSuccess() {
        when(idempotencyKeyRepository.findByCustomerIdAndOperationAndIdempotencyKey(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(dropRepository.existsById(10L)).thenReturn(true);
        when(dropRepository.allocateInventory(10L, 2)).thenReturn(1);
        when(holdRepository.save(any(HoldEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HoldResponse response = holdService.createHold(10L, new CreateHoldRequest(2), "key-1");

        assertThat(response.dropId()).isEqualTo(10L);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(response.customerId()).isEqualTo("cust-1");
        verify(outboxService).appendHoldEvent(any(), anyString(), eq(10L), eq("cust-1"), eq(2));
        verify(metrics).recordHoldCreationSuccess();
        verify(dropQueryService).invalidateDrop(10L);

        ArgumentCaptor<com.limiteddrop.reservation.entity.IdempotencyKeyEntity> keyCaptor =
                ArgumentCaptor.forClass(com.limiteddrop.reservation.entity.IdempotencyKeyEntity.class);
        verify(idempotencyKeyRepository).save(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getOperation()).isEqualTo(IdempotencyOperation.CREATE_HOLD);
    }

    @Test
    void createHoldInsufficientInventoryReturnsConflict() {
        when(idempotencyKeyRepository.findByCustomerIdAndOperationAndIdempotencyKey(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(dropRepository.existsById(10L)).thenReturn(true);
        when(dropRepository.allocateInventory(10L, 5)).thenReturn(0);

        assertThatThrownBy(() -> holdService.createHold(10L, new CreateHoldRequest(5), "key-2"))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("INSUFFICIENT_INVENTORY");

        verify(holdRepository, never()).save(any());
        verify(metrics).recordHoldCreationFailure();
    }

    @Test
    void createHoldIdempotentDuplicateSamePayload() throws Exception {
        HoldResponse stored = new HoldResponse(
                "hold-1", 10L, "cust-1", 1, HoldStatus.ACTIVE,
                Instant.now().plusSeconds(60), Instant.now(), Instant.now()
        );
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("dropId", 10L);
        payload.put("quantity", 1);
        String hash = idempotencyService.hashRequest(payload);
        var entity = new com.limiteddrop.reservation.entity.IdempotencyKeyEntity();
        entity.setCustomerId("cust-1");
        entity.setOperation(IdempotencyOperation.CREATE_HOLD);
        entity.setIdempotencyKey("dup-key");
        entity.setRequestHash(hash);
        entity.setResponseBody(objectMapper.writeValueAsString(stored));
        entity.setHttpStatus(201);
        entity.setCreatedAt(Instant.now());

        when(idempotencyKeyRepository.findByCustomerIdAndOperationAndIdempotencyKey(
                "cust-1", IdempotencyOperation.CREATE_HOLD, "dup-key"))
                .thenReturn(Optional.of(entity));

        HoldResponse response = holdService.createHold(10L, new CreateHoldRequest(1), "dup-key");

        assertThat(response.id()).isEqualTo("hold-1");
        verify(dropRepository, never()).allocateInventory(anyLong(), anyInt());
    }

    @Test
    void createHoldSameKeyDifferentPayloadConflict() {
        var entity = new com.limiteddrop.reservation.entity.IdempotencyKeyEntity();
        entity.setRequestHash("different-hash");
        entity.setResponseBody("{}");
        entity.setHttpStatus(201);
        when(idempotencyKeyRepository.findByCustomerIdAndOperationAndIdempotencyKey(
                anyString(), anyString(), eq("reuse-key")))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> holdService.createHold(10L, new CreateHoldRequest(2), "reuse-key"))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void getHoldOwnershipForbidden() {
        HoldEntity hold = activeHold("hold-x", "other-customer");
        when(holdRepository.findById("hold-x")).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.getHold("hold-x"))
                .isInstanceOf(ForbiddenException.class);
    }

    private static void authenticate(String customerId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CustomerPrincipal(customerId), null, List.of())
        );
    }

    private static HoldEntity activeHold(String id, String customerId) {
        HoldEntity hold = new HoldEntity();
        hold.setId(id);
        hold.setDropId(10L);
        hold.setCustomerId(customerId);
        hold.setQuantity(1);
        hold.setStatus(HoldStatus.ACTIVE);
        hold.setExpiresAt(Instant.now().plusSeconds(120));
        hold.setCreatedAt(Instant.now());
        hold.setUpdatedAt(Instant.now());
        return hold;
    }

}
