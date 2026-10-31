package com.limiteddrop.reservation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.limiteddrop.reservation.api.dto.DropResponse;
import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.exception.ForbiddenException;
import com.limiteddrop.reservation.exception.GlobalExceptionHandler;
import com.limiteddrop.reservation.security.CustomerIdAuthenticationFilter;
import com.limiteddrop.reservation.security.SecurityConfig;
import com.limiteddrop.reservation.service.DropQueryService;
import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.web.RequestIdFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DropController.class)
@Import({SecurityConfig.class, CustomerIdAuthenticationFilter.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class DropControllerWebMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean DropQueryService dropQueryService;
    @MockBean HoldService holdService;

    @Test
    void listDropsPublic() throws Exception {
        when(dropQueryService.listDrops()).thenReturn(List.of(
                new DropResponse(1L, "Drop", "desc", 10, 10, 0, 0, DropStatus.LIVE,
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"),
                        Instant.now(), Instant.now())
        ));

        mockMvc.perform(get("/api/v1/drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Drop"));
    }

    @Test
    void createHoldRequiresCustomerHeader() throws Exception {
        mockMvc.perform(post("/api/v1/drops/1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k1")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createHoldSuccess() throws Exception {
        when(holdService.createHold(eq(1L), any(), eq("k1"))).thenReturn(
                new HoldResponse("h1", 1L, "cust-1", 1, HoldStatus.ACTIVE,
                        Instant.now().plusSeconds(60), Instant.now(), Instant.now())
        );

        mockMvc.perform(post("/api/v1/drops/1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", "cust-1")
                        .header("Idempotency-Key", "k1")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("h1"));
    }

    @Test
    void createHoldInsufficientInventoryConflict() throws Exception {
        when(holdService.createHold(eq(1L), any(), eq("k2")))
                .thenThrow(new ConflictException("INSUFFICIENT_INVENTORY", "Unable to allocate"));

        mockMvc.perform(post("/api/v1/drops/1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", "cust-1")
                        .header("Idempotency-Key", "k2")
                        .content("{\"quantity\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"));
    }

    @Test
    void ownershipMappedToForbidden() throws Exception {
        when(holdService.createHold(eq(1L), any(), eq("k3")))
                .thenThrow(new ForbiddenException("HOLD_FORBIDDEN", "not yours"));

        mockMvc.perform(post("/api/v1/drops/1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", "cust-1")
                        .header("Idempotency-Key", "k3")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOLD_FORBIDDEN"));
    }
}
