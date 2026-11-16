package com.mmts.booking.service;

import com.mmts.booking.adapter.StubLicenceClient;
import com.mmts.booking.adapter.StubPaymentClient;
import com.mmts.booking.adapter.StubProviderHubClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo only: switches on failures in the stand-ins for Identity service, Payment service and Provider Hub service,
 * so the saga's undo can be shown live. By default every stub succeeds.
 */
@Service
@Profile("demo")
public class DemoControlService {

    private final StubPaymentClient payments;
    private final StubProviderHubClient providerHub;
    private final StubLicenceClient licences;
    private final DriveEligibilityService eligibility;

    public DemoControlService(StubPaymentClient payments, StubProviderHubClient providerHub, StubLicenceClient licences,
                              DriveEligibilityService eligibility) {
        this.payments = payments;
        this.providerHub = providerHub;
        this.licences = licences;
        this.eligibility = eligibility;
    }

    public void failNextPayment(String reason) {
        payments.failNextPayment(reason);
    }

    public void refuseHolds(String providerId) {
        providerHub.refuseHoldsFrom(providerId);
    }

    public void failNextConfirm() {
        providerHub.failNextConfirm();
    }

    public void makeIneligible(String userId, String reason) {
        licences.markIneligible(userId, reason);
        eligibility.forget(userId);                  // the cached "yes" must not hide the change
    }

    public void reset() {
        payments.reset();
        providerHub.reset();
        licences.ineligibleUsers().keySet().forEach(eligibility::forget);
        licences.reset();
    }

    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("nextPaymentFailsWith", payments.pendingFailure());
        state.put("providersRefusingHolds", providerHub.refusingProviders());
        state.put("nextConfirmFails", providerHub.confirmWillFail());
        state.put("ineligibleUsers", licences.ineligibleUsers());
        return state;
    }
}
