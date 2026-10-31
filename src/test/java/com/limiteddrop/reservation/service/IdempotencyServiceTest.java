package com.limiteddrop.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.entity.IdempotencyKeyEntity;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.repository.IdempotencyKeyRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock IdempotencyKeyRepository repository;

    IdempotencyService service;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, objectMapper);
    }

    @Test
    void beginReturnsStoredWhenHashMatches() {
        String hash = service.hashRequest(Map.of("a", 1));
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.setRequestHash(hash);
        entity.setResponseBody("{\"ok\":true}");
        entity.setHttpStatus(200);
        entity.setCreatedAt(Instant.now());
        when(repository.findByCustomerIdAndOperationAndIdempotencyKey("c", "OP", "k"))
                .thenReturn(Optional.of(entity));

        Optional<IdempotencyService.StoredResponse> result = service.begin("c", "OP", "k", hash);

        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(200);
    }

    @Test
    void beginThrowsWhenHashDiffers() {
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.setRequestHash("old");
        when(repository.findByCustomerIdAndOperationAndIdempotencyKey("c", "OP", "k"))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.begin("c", "OP", "k", "new"))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }
}
