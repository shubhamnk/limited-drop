package com.limiteddrop.reservation.api;

import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.service.CancellationService;
import com.limiteddrop.reservation.service.ConfirmationService;
import com.limiteddrop.reservation.service.HoldService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

    private final HoldService holdService;
    private final ConfirmationService confirmationService;
    private final CancellationService cancellationService;

    public HoldController(
            HoldService holdService,
            ConfirmationService confirmationService,
            CancellationService cancellationService
    ) {
        this.holdService = holdService;
        this.confirmationService = confirmationService;
        this.cancellationService = cancellationService;
    }

    @GetMapping("/{holdId}")
    public HoldResponse getHold(@PathVariable String holdId) {
        return holdService.getHold(holdId);
    }

    @PostMapping("/{holdId}/confirm")
    public HoldResponse confirmHold(
            @PathVariable String holdId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return confirmationService.confirmHold(holdId, idempotencyKey);
    }

    @PostMapping("/{holdId}/cancel")
    public HoldResponse cancelHold(
            @PathVariable String holdId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return cancellationService.cancelHold(holdId, idempotencyKey);
    }
}
