package com.mmts.booking.service.saga;

import com.mmts.booking.adapter.ProviderHubClient;
import com.mmts.booking.adapter.ProviderHubClient.ConfirmResult;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.LegStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 4: now that the money is taken, confirm every hold with its provider (INT-12).
 * For a TAXI leg the confirmation assigns a driver (M3.5).
 * Undo: nothing here; the hold step's undo releases confirmed holds too, and the payment step's undo refunds.
 */
@Component
public class ConfirmLegsStep implements SagaStep {

    private final ProviderHubClient providerHub;

    public ConfirmLegsStep(ProviderHubClient providerHub) {
        this.providerHub = providerHub;
    }

    @Override
    public String name() {
        return "CONFIRM_LEGS";
    }

    @Override
    public String execute(SagaContext context) {
        List<String> confirmed = new ArrayList<>();
        for (BookingLeg leg : context.booking().getLegs()) {
            if (leg.getStatus() != LegStatus.HELD) {
                continue;
            }
            try {
                ConfirmResult result = providerHub.confirm(leg.getHoldId());
                leg.markConfirmed();
                context.confirmations().put(leg.getId(), result);
                confirmed.add(leg.getHoldId());
            } catch (ApiException e) {
                throw new StepFailed("CONFIRM_FAILED", leg.getProviderId() + " could not confirm " + leg.getId()
                        + " (" + e.getCode() + ")");
            }
        }
        return "confirmed " + confirmed;
    }

    @Override
    public String undo(SagaContext context) {
        return null;
    }
}
