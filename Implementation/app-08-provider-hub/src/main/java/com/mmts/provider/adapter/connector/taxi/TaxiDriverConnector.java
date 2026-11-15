package com.mmts.provider.adapter.connector.taxi;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.domain.ProviderDefinition;
import com.mmts.provider.service.DriverService;
import org.springframework.stereotype.Component;

/**
 * Plug-in 5: a taxi fleet whose drivers use our driver app (APP-02).
 * Unlike the other plug-ins the "provider system" is our own driver pool, so this plug-in uses the core's
 * DriverService (drivers' status and location come from INT-07).
 *
 * hold    = check that a free, activated, online driver is within "max-pickup-km"
 * confirm = give the ride to the nearest such driver (DRIVER_ASSIGNED) and push them a ride offer (INT-08)
 * release = free the driver and cancel the offer
 */
@Component
public class TaxiDriverConnector implements ProviderConnector {

    private final DriverService drivers;

    public TaxiDriverConnector(DriverService drivers) {
        this.drivers = drivers;
    }

    @Override
    public String type() {
        return "taxi-driver";
    }

    @Override
    public HoldQuote hold(ProviderDefinition provider, LegRequest leg) {
        double maxKm = provider.doubleSetting("max-pickup-km", 5);
        if (!drivers.hasFreeDriverNear(provider.getId(), leg.from(), maxKm)) {
            throw ProviderConnector.notAvailable("No free " + provider.getId() + " driver within " + maxKm + " km");
        }
        return new HoldQuote(provider.priceFor(leg.from(), leg.to()), null, "ride-" + leg.holdId());
    }

    @Override
    public AccessGrant confirm(ProviderDefinition provider, LegRequest leg, String providerRef) {
        AssignedDriver driver = drivers.assignNearest(provider.getId(), leg.holdId(), leg.from(), leg.to(),
                leg.pricePaise(), provider.doubleSetting("max-pickup-km", 5),
                provider.doubleSetting("avg-speed-kmph", 20));
        return AccessGrant.of("DRIVER_ASSIGNED", driver.vehicleNumber(), leg.startTime(), 60, driver);
    }

    @Override
    public void release(ProviderDefinition provider, LegRequest leg, String providerRef) {
        drivers.releaseAssignment(leg.holdId());
    }
}
