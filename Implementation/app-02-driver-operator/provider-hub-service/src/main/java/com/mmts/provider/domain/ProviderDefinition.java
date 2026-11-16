package com.mmts.provider.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One transport provider as written in application.yml (under mmts.provider-hub.providers).
 * The "connector" field names the plug-in that talks to this provider, for example "gbfs-scooter".
 * Adding a provider = adding one of these blocks; no core code changes (driver D10).
 */
public class ProviderDefinition {

    private String id;                       // filled in from the map key, e.g. "ZIPRIDE"
    private String name;
    private String connector;                // plug-in type, e.g. "ondc-metro"
    private boolean enabled = true;
    private List<Mode> modes = new ArrayList<>();
    private long basePricePaise;
    private long perKmPaise;
    private List<String> operators = new ArrayList<>();          // user ids of this provider's OPERATOR staff
    private Map<String, String> settings = new LinkedHashMap<>(); // plug-in specific settings

    /** Simple distance-based price: base + per-km rate, in paise. Used by most plug-ins. */
    public long priceFor(Place from, Place to) {
        return basePricePaise + Math.round(perKmPaise * from.distanceKmTo(to));
    }

    public boolean isOperator(String userId) {
        return operators.contains(userId);
    }

    public String setting(String key, String fallback) {
        return settings.getOrDefault(key, fallback);
    }

    public int intSetting(String key, int fallback) {
        String value = settings.get(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    public double doubleSetting(String key, double fallback) {
        String value = settings.get(key);
        return value == null ? fallback : Double.parseDouble(value.trim());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConnector() { return connector; }
    public void setConnector(String connector) { this.connector = connector; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<Mode> getModes() { return modes; }
    public void setModes(List<Mode> modes) { this.modes = modes; }
    public long getBasePricePaise() { return basePricePaise; }
    public void setBasePricePaise(long basePricePaise) { this.basePricePaise = basePricePaise; }
    public long getPerKmPaise() { return perKmPaise; }
    public void setPerKmPaise(long perKmPaise) { this.perKmPaise = perKmPaise; }
    public List<String> getOperators() { return operators; }
    public void setOperators(List<String> operators) { this.operators = operators; }
    public Map<String, String> getSettings() { return settings; }
    public void setSettings(Map<String, String> settings) { this.settings = settings; }
}
