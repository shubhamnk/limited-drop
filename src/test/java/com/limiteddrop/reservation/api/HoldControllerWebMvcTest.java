package com.limiteddrop.reservation.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.exception.ConflictException;
import com.limiteddrop.reservation.exception.ForbiddenException;
import com.limiteddrop.reservation.exception.GlobalExceptionHandler;
import com.limiteddrop.reservation.security.CustomerIdAuthenticationFilter;
import com.limiteddrop.reservation.security.SecurityConfig;
import com.limiteddrop.reservation.service.CancellationService;
import com.limiteddrop.reservation.service.ConfirmationService;
import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.web.RequestIdFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HoldController.class)
@Import({SecurityConfig.class, CustomerIdAuthenticationFilter.class, RequestIdFilter.class, GlobalExceptionHandler.class})
class HoldControllerWebMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean HoldService holdService;
    @MockBean ConfirmationService confirmationService;
    @MockBean CancellationService cancellationService;

    @Test
    void getHoldRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/holds/h1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getHoldSuccess() throws Exception {
        when(holdService.getHold("h1")).thenReturn(sample("h1", HoldStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/holds/h1").header("X-Customer-Id", "cust-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("h1"));
    }

    @Test
    void confirmActive() throws Exception {
        when(confirmationService.confirmHold(eq("h1"), eq("idem-c")))
                .thenReturn(sample("h1", HoldStatus.CONFIRMED));

        mockMvc.perform(post("/api/v1/holds/h1/confirm")
                        .header("X-Customer-Id", "cust-1")
                        .header("Idempotency-Key", "idem-c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmExpiredConflict() throws Exception {
        when(confirmationService.confirmHold(eq("h2"), eq("idem-e")))
                .thenThrow(new ConflictException("HOLD_EXPIRED", "expired"));

        mockMvc.perform(post("/api/v1/holds/h2/confirm")
                        .header("X-Customer-Id", "cust-1")
                        .header("Idempotency-Key", "idem-e"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    void cancelActive() throws Exception {
        when(cancellationService.cancelHold(eq("h3"), eq("idem-x")))
                .thenReturn(sample("h3", HoldStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/holds/h3/cancel")
                        .header("X-Customer-Id", "cust-1")
                        .header("Idempotency-Key", "idem-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void ownershipForbidden() throws Exception {
        when(holdService.getHold("h4")).thenThrow(new ForbiddenException("HOLD_FORBIDDEN", "nope"));

        mockMvc.perform(get("/api/v1/holds/h4").header("X-Customer-Id", "cust-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOLD_FORBIDDEN"));
    }

    private static HoldResponse sample(String id, HoldStatus status) {
        return new HoldResponse(id, 1L, "cust-1", 1, status,
                Instant.now().plusSeconds(60), Instant.now(), Instant.now());
    }
}
