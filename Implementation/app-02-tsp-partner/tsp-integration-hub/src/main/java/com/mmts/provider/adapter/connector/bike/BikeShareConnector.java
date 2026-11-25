package com.mmts.provider.adapter.connector.bike;

import com.mmts.provider.adapter.connector.gbfs.GbfsConnectorSupport;
import com.mmts.provider.domain.Mode;
import org.springframework.stereotype.Component;

/**
 * Plug-in 6: a pedal bike-share operator (HOPBIKE) with a GBFS 2.3 free_bike_status feed.
 *
 * This plug-in is installed (it is a Spring bean) but its provider is DISABLED in application.yml.
 * Setting mmts.provider-hub.providers.HOPBIKE.enabled=true is all it takes to add the provider:
 * no core class changes (driver D10). See NewProviderByConfigurationTest.
 */
@Component
public class BikeShareConnector extends GbfsConnectorSupport {

    @Override
    public String type() {
        return "gbfs-bike";
    }

    @Override
    protected Mode mode() {
        return Mode.BIKE;
    }
}
