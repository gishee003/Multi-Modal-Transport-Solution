package com.mmts.payment.service;

import com.mmts.payment.adapter.PaymentGateway;
import com.mmts.payment.api.PaymentDtos.Outcome;
import com.mmts.payment.api.WalletDtos.TopUpRequest;
import com.mmts.payment.api.WalletDtos.WalletResponse;
import com.mmts.payment.api.WalletDtos.WalletTransactionResponse;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.config.PaymentSettings;
import com.mmts.payment.domain.LedgerAccounts;
import com.mmts.payment.domain.LedgerTransaction;
import com.mmts.payment.domain.Wallet;
import com.mmts.payment.domain.WalletTransaction;
import com.mmts.payment.repository.WalletRepository;
import com.mmts.payment.repository.WalletTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Wallet functions (M4.1): create on first use, top up through the gateway, show balance and history.
 */
@Service
public class WalletService {

    public static final String CURRENCY = "INR";
    public static final String TOKEN_PREFIX = "tok_";

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final PaymentEvents events;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final long maxTopUpPaise;

    public WalletService(WalletRepository wallets, WalletTransactionRepository transactions, PaymentGateway gateway,
                         LedgerService ledger, PaymentEvents events, Clock clock,
                         PlatformTransactionManager transactionManager, PaymentSettings settings) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.gateway = gateway;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxTopUpPaise = settings.wallet().maxTopUpPaise();
    }

    /** The caller's own wallet; made with balance 0 the first time it is asked for. */
    @Transactional
    public WalletResponse myWallet(String userId) {
        return toResponse(getOrCreate(userId));
    }

    /** Any user's wallet, for finance / support staff. */
    @Transactional(readOnly = true)
    public WalletResponse walletOf(String userId) {
        return wallets.findById(userId).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "No wallet for user " + userId));
    }

    @Transactional(readOnly = true)
    public List<WalletTransactionResponse> history(String userId) {
        return transactions.findTop50ByUserIdOrderByIdDesc(userId).stream().map(WalletService::toResponse).toList();
    }

    /**
     * Top-up: the traveller paid on the gateway's checkout page and sends us only the token it returned.
     * 1. check the amount and that we got a token (not card data)
     * 2. if this Idempotency-Key was seen before, return that result (no second charge)
     * 3. charge the token through the gateway
     * 4. in one database transaction: add to the wallet, write the history line and the ledger lines
     */
    public Outcome<WalletTransactionResponse> topUp(String userId, String idempotencyKey, TopUpRequest request,
                                                    AuthenticatedUser actor) {
        long amount = request.amountPaise();
        if (amount > maxTopUpPaise) {
            throw ApiException.badRequest("TOP_UP_LIMIT_EXCEEDED",
                    "A single top-up can be at most " + maxTopUpPaise + " paise");
        }
        if (!request.paymentToken().startsWith(TOKEN_PREFIX)) {
            throw ApiException.badRequest("INVALID_PAYMENT_TOKEN",
                    "Send the token from the gateway checkout (it starts with tok_), never card details");
        }
        if (idempotencyKey != null) {
            var earlier = transactions.findByIdempotencyKey(idempotencyKey);
            if (earlier.isPresent()) {
                return replay(earlier.get(), userId, amount);
            }
        }

        PaymentGateway.ChargeResult charge = gateway.charge(request.paymentToken(), amount, "topup:" + userId);
        if (!charge.captured()) {
            events.audit(actor, "WALLET_TOP_UP", "WALLET", userId, "DECLINED");
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "TOP_UP_DECLINED",
                    "The payment gateway declined the top-up; no money was taken");
        }

        try {
            WalletTransaction saved = OptimisticRetry.run(() -> tx.execute(status -> {
                Wallet wallet = getOrCreate(userId);
                wallet.credit(amount, now());
                WalletTransaction line = recordMovement(wallet, WalletTransaction.Type.TOP_UP, amount,
                        charge.gatewayPaymentId(), idempotencyKey);
                ledger.post(LedgerTransaction.of("TOPUP:" + line.transactionId(),
                                "Wallet top-up via gateway payment " + charge.gatewayPaymentId())
                        .debit(LedgerAccounts.GATEWAY_CLEARING, amount)
                        .credit(LedgerAccounts.userWallet(userId), amount));
                return line;
            }));
            events.audit(actor, "WALLET_TOP_UP", "WALLET", userId, "SUCCESS");
            return new Outcome<>(toResponse(saved), true);
        } catch (DataIntegrityViolationException e) {
            // Another request with the same key finished first: answer with its result.
            return replay(transactions.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e), userId, amount);
        }
    }

    private Outcome<WalletTransactionResponse> replay(WalletTransaction earlier, String userId, long amount) {
        if (!earlier.getUserId().equals(userId) || earlier.getAmountPaise() != amount) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
        return new Outcome<>(toResponse(earlier), false);
    }

    /** Finds the wallet or creates an empty one. Must run inside a transaction. */
    public Wallet getOrCreate(String userId) {
        return wallets.findById(userId).orElseGet(() -> wallets.save(new Wallet(userId, now())));
    }

    /** Writes one history line after the wallet balance has changed. Must run inside a transaction. */
    public WalletTransaction recordMovement(Wallet wallet, WalletTransaction.Type type, long amountPaise, String reference,
                                     String idempotencyKey) {
        return transactions.save(new WalletTransaction(wallet.getUserId(), type, amountPaise,
                wallet.getBalancePaise(), reference, idempotencyKey, now()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private WalletResponse toResponse(Wallet w) {
        return new WalletResponse(w.getUserId(), w.getBalancePaise(), CURRENCY, w.getUpdatedAt());
    }

    private static WalletTransactionResponse toResponse(WalletTransaction t) {
        return new WalletTransactionResponse(t.transactionId(), t.getType().name(), t.getAmountPaise(),
                t.getBalanceAfterPaise(), t.getReference(), t.getCreatedAt());
    }
}
