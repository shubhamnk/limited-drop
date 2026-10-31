package com.limiteddrop.reservation.api;

import com.limiteddrop.reservation.api.dto.CreateHoldRequest;
import com.limiteddrop.reservation.api.dto.DropResponse;
import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.service.DropQueryService;
import com.limiteddrop.reservation.service.HoldService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drops")
public class DropController {

    private final DropQueryService dropQueryService;
    private final HoldService holdService;

    public DropController(DropQueryService dropQueryService, HoldService holdService) {
        this.dropQueryService = dropQueryService;
        this.holdService = holdService;
    }

    @GetMapping
    public List<DropResponse> listDrops() {
        return dropQueryService.listDrops();
    }

    @GetMapping("/{dropId}")
    public DropResponse getDrop(@PathVariable Long dropId) {
        return dropQueryService.getDrop(dropId);
    }

    @PostMapping("/{dropId}/holds")
    public ResponseEntity<HoldResponse> createHold(
            @PathVariable Long dropId,
            @Valid @RequestBody CreateHoldRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        HoldResponse response = holdService.createHold(dropId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
