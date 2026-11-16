package com.mmts.provider.api;

import com.mmts.provider.api.dto.HoldDtos.ConfirmResponse;
import com.mmts.provider.api.dto.HoldDtos.HoldRequest;
import com.mmts.provider.api.dto.HoldDtos.HoldResponse;
import com.mmts.provider.api.dto.HoldDtos.UnlockRequest;
import com.mmts.provider.api.dto.HoldDtos.UnlockResponse;
import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.service.HoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** INT-12, called by Booking service with a SERVICE token. Exactly as in docs/CONTRACTS.md. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('SERVICE')")
@Tag(name = "INT-12 Holds", description = "Hold, confirm, release and unlock a leg (role SERVICE)")
public class HoldController {

    private final HoldService holds;

    public HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/holds")
    @Operation(summary = "Hold a leg for 10 minutes")
    public ResponseEntity<HoldResponse> hold(@Valid @RequestBody HoldRequest request,
                                             @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holds.hold(request, caller));
    }

    @PostMapping("/holds/{holdId}/confirm")
    @Operation(summary = "Confirm a hold (after payment): returns QR ticket, unlock code or assigned driver")
    public ConfirmResponse confirm(@PathVariable String holdId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return holds.confirm(holdId, caller);
    }

    @DeleteMapping("/holds/{holdId}")
    @Operation(summary = "Release a hold (undo). Safe to repeat: 204 every time")
    public ResponseEntity<Void> release(@PathVariable String holdId, @AuthenticationPrincipal AuthenticatedUser caller) {
        holds.release(holdId, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/vehicles/{vehicleId}/unlock")
    @Operation(summary = "Unlock the vehicle of a confirmed hold when the leg starts")
    public UnlockResponse unlock(@PathVariable String vehicleId, @Valid @RequestBody UnlockRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        return holds.unlock(vehicleId, request, caller);
    }
}
