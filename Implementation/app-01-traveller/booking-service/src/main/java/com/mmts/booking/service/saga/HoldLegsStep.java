package com.mmts.booking.service.saga;

import com.mmts.booking.adapter.ProviderHubClient;
import com.mmts.booking.adapter.ProviderHubClient.HoldRequest;
import com.mmts.booking.adapter.ProviderHubClient.HoldResult;
import com.mmts.booking.adapter.ProviderHubClient.PlaceRef;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.domain.Place;
import com.mmts.booking.service.PricingRules;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 2 (M3.2): ask each provider to hold its leg (INT-12), and add up the price.
 * Undo: release every hold that was taken, even if only some legs were held before a provider said no.
 */
@Component
public class HoldLegsStep implements SagaStep {

    private final ProviderHubClient providerHub;
    private final PricingRules pricing;

    public HoldLegsStep(ProviderHubClient providerHub, PricingRules pricing) {
        this.providerHub = providerHub;
        this.pricing = pricing;
    }

    @Override
    public String name() {
        return "HOLD_LEGS";
    }

    @Override
    public String execute(SagaContext context) {
        Booking booking = context.booking();
        List<String> held = new ArrayList<>();
        for (BookingLeg leg : booking.getLegs()) {
            if (!leg.getMode().needsProvider()) {
                continue;                                  // walking: nothing to hold
            }
            try {
                HoldResult hold = providerHub.hold(new HoldRequest(booking.getId(), leg.getId(), leg.getProviderId(),
                        leg.getMode().name(), ref(leg.getFromPlace()), ref(leg.getToPlace()), leg.getStartTime(),
                        leg.getVehicleId()));
                leg.markHeld(hold.holdId(), pricing.priceFor(leg.getMode(), hold.pricePaise()));
                held.add(hold.holdId());
            } catch (ApiException e) {
                throw new StepFailed("HOLD_REFUSED", leg.getProviderId() + " refused " + leg.getId() + " (" + e.getCode() + ")");
            }
        }
        long total = booking.getLegs().stream().mapToLong(BookingLeg::getPricePaise).sum();
        booking.recordTotal(total);
        booking.moveTo(BookingStatus.LEGS_HELD, context.now());
        return "held " + held + ", total " + total + " paise";
    }

    @Override
    public String undo(SagaContext context) {
        List<String> released = new ArrayList<>();
        for (BookingLeg leg : context.booking().getLegs()) {
            if (leg.hasActiveHold()) {
                providerHub.release(leg.getHoldId());      // safe to repeat (INT-12)
                leg.markReleased();
                released.add(leg.getHoldId());
            }
        }
        return released.isEmpty() ? null : "released " + released;
    }

    private static PlaceRef ref(Place place) {
        return new PlaceRef(place.getName(), place.getLat(), place.getLon(), place.getStopId());
    }
}
