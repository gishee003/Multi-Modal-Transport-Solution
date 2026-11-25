package com.mmts.payment.api;

import com.mmts.payment.api.PaymentDtos.Outcome;
import com.mmts.payment.api.WalletDtos.TopUpRequest;
import com.mmts.payment.api.WalletDtos.WalletResponse;
import com.mmts.payment.api.WalletDtos.WalletTransactionResponse;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** INT-05 Wallet. Travellers see and top up only their own wallet ("me" = the user in the token). */
@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallet (INT-05)")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('TRAVELLER')")
    @Operation(summary = "My wallet balance (the wallet is created on first use)")
    public WalletResponse myWallet(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.myWallet(user.userId());
    }

    @PostMapping("/me/top-ups")
    @PreAuthorize("hasRole('TRAVELLER')")
    @Operation(summary = "Top up my wallet with a gateway token (never card details)",
            description = "Example body: {\"amountPaise\":20000,\"paymentToken\":\"tok_demo_visa\"}. "
                    + "Optional Idempotency-Key header: a repeat returns 200 with the same body.")
    public ResponseEntity<WalletTransactionResponse> topUp(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TopUpRequest request) {
        Outcome<WalletTransactionResponse> outcome = walletService.topUp(user.userId(), idempotencyKey, request, user);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.body());
    }

    @GetMapping("/me/transactions")
    @PreAuthorize("hasRole('TRAVELLER')")
    @Operation(summary = "My wallet history (newest first, at most 50)")
    public List<WalletTransactionResponse> myHistory(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.history(user.userId());
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPPORT')")
    @Operation(summary = "Any user's wallet (finance, admin and support staff)")
    public WalletResponse walletOf(@PathVariable String userId) {
        return walletService.walletOf(userId);
    }

    @GetMapping("/{userId}/transactions")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPPORT')")
    @Operation(summary = "Any user's wallet history (finance, admin and support staff)")
    public List<WalletTransactionResponse> historyOf(@PathVariable String userId) {
        return walletService.history(userId);
    }
}
