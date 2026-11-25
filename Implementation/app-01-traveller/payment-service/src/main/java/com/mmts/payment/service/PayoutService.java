package com.mmts.payment.service;

import com.mmts.payment.api.FinanceDtos.PayoutResponse;
import com.mmts.payment.api.FinanceDtos.PayoutRunResponse;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.domain.Payout;
import com.mmts.payment.repository.PayoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Runs the payout job (on demand or from the daily scheduler) and lists past payouts. */
@Service
public class PayoutService {

    private final ProviderPayoutJob job;
    private final PayoutRepository payouts;
    private final PaymentEvents events;

    public PayoutService(ProviderPayoutJob job, PayoutRepository payouts, PaymentEvents events) {
        this.job = job;
        this.payouts = payouts;
        this.events = events;
    }

    /** @param trigger "MANUAL" (endpoint) or "SCHEDULED" (daily job) */
    @Transactional
    public PayoutRunResponse runNow(String trigger, AuthenticatedUser actor) {
        PayoutRunResponse result = job.run(trigger);
        events.audit(actor, "PAYOUT_RUN", "PAYOUT_RUN", result.runId(), "SUCCESS");
        return result;
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> recent() {
        return payouts.findTop100ByOrderByIdDesc().stream().map(PayoutService::toResponse).toList();
    }

    static PayoutResponse toResponse(Payout p) {
        return new PayoutResponse(p.payoutId(), p.getRunId(), p.getProviderId(), p.getAmountPaise(), p.getStatus(),
                p.getTransferReference(), p.getFailureReason(), p.getCreatedAt());
    }
}
