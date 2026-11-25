package com.mmts.provider.support;

import com.mmts.provider.domain.Mode;
import com.mmts.provider.domain.Place;
import com.mmts.provider.domain.ProviderDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds provider definitions and places for unit tests (the same shape application.yml produces). */
public final class TestProviders {

    public static final Place RAJIV_CHOWK = new Place("Rajiv Chowk Metro", 28.6328, 77.2197, "DMRC-RCK");
    public static final Place INDIA_GATE = new Place("India Gate", 28.6129, 77.2295, null);
    public static final Place RAJIV_CHOWK_BUS = new Place("Rajiv Chowk Bus Stop", 28.6315, 77.2167, "DTC-S01");
    public static final Place INDIA_GATE_BUS = new Place("India Gate", 28.6129, 77.2295, "DTC-S03");

    private TestProviders() {
    }

    /** settings: key, value, key, value ... */
    public static ProviderDefinition provider(String id, String connector, Mode mode, boolean enabled,
                                              String... settings) {
        ProviderDefinition d = new ProviderDefinition();
        d.setId(id);
        d.setName(id + " test provider");
        d.setConnector(connector);
        d.setEnabled(enabled);
        d.setModes(List.of(mode));
        d.setBasePricePaise(5000);
        d.setPerKmPaise(4000);
        d.setOperators(List.of("op-" + id.toLowerCase()));
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < settings.length; i += 2) {
            map.put(settings[i], settings[i + 1]);
        }
        d.setSettings(map);
        return d;
    }
}
