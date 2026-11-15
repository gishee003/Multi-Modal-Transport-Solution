package com.mmts.provider.adapter.connector.gbfs;

import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.ProviderDefinition;
import org.springframework.stereotype.Component;

/**
 * Plug-in 3: shared electric scooters (e.g. ZIPRIDE) published through a GBFS 3.0 vehicle_status feed.
 * Extra rule over bikes: a scooter below "min-battery-pct" (application.yml) cannot be rented.
 */
@Component
public class ScooterGbfsConnector extends GbfsConnectorSupport {

    @Override
    public String type() {
        return "gbfs-scooter";
    }

    @Override
    protected Mode mode() {
        return Mode.SCOOTER;
    }

    @Override
    protected boolean usable(ProviderDefinition provider, Slot slot) {
        Integer battery = slot.vehicle.batteryPct();
        boolean enoughBattery = battery == null || battery >= provider.intSetting("min-battery-pct", 20);
        return super.usable(provider, slot) && enoughBattery;
    }
}
