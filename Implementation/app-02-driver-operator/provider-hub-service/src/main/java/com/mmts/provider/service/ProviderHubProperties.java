package com.mmts.provider.service;

import com.mmts.provider.domain.ProviderDefinition;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * All Provider Hub service settings from application.yml, under "mmts.provider-hub" (Externalization pattern).
 * The "providers" map is the list of plugged-in providers: key = provider id, value = its definition.
 */
@ConfigurationProperties(prefix = "mmts.provider-hub")
public class ProviderHubProperties {

    private int holdMinutes = 10;
    private int offlineAfterSeconds = 60;
    private int lowBatteryPct = 20;
    private int connectorRetries = 3;
    private long retryBackoffMs = 200;
    private long offerStreamTimeoutMs = 1_800_000;
    private Map<String, ProviderDefinition> providers = new LinkedHashMap<>();

    /** Copies each map key (upper case) into the provider's id. Safe to call more than once. */
    @PostConstruct
    public void assignIds() {
        providers.forEach((key, definition) -> definition.setId(key.toUpperCase(Locale.ROOT)));
    }

    /** An enabled provider by id (any letter case). */
    public Optional<ProviderDefinition> findEnabled(String providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        assignIds();
        return providers.values().stream()
                .filter(p -> p.isEnabled() && p.getId().equalsIgnoreCase(providerId))
                .findFirst();
    }

    public int getHoldMinutes() { return holdMinutes; }
    public void setHoldMinutes(int holdMinutes) { this.holdMinutes = holdMinutes; }
    public int getOfflineAfterSeconds() { return offlineAfterSeconds; }
    public void setOfflineAfterSeconds(int offlineAfterSeconds) { this.offlineAfterSeconds = offlineAfterSeconds; }
    public int getLowBatteryPct() { return lowBatteryPct; }
    public void setLowBatteryPct(int lowBatteryPct) { this.lowBatteryPct = lowBatteryPct; }
    public int getConnectorRetries() { return connectorRetries; }
    public void setConnectorRetries(int connectorRetries) { this.connectorRetries = connectorRetries; }
    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
    public long getOfferStreamTimeoutMs() { return offerStreamTimeoutMs; }
    public void setOfferStreamTimeoutMs(long offerStreamTimeoutMs) { this.offerStreamTimeoutMs = offerStreamTimeoutMs; }
    public Map<String, ProviderDefinition> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderDefinition> providers) { this.providers = providers; }
}
