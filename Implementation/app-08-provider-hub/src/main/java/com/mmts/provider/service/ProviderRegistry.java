package com.mmts.provider.service;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.adapter.connector.ProviderConnector.ProviderUnavailableException;
import com.mmts.provider.api.dto.FleetDtos.ProviderView;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.domain.ProviderDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The heart of the microkernel (M5.1): the provider registry, which is also a Factory.
 *
 * At start-up Spring hands us every installed plug-in (every bean that implements ProviderConnector).
 * For each ENABLED provider in application.yml we look up the plug-in whose type() matches the
 * provider's "connector" name. This class never names a concrete plug-in class, so adding a provider
 * or a plug-in never changes it (Open/Closed principle, driver D10).
 *
 * It also wraps every call to a plug-in with retries (core concern, not each plug-in's).
 */
@Service
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /** A provider together with the plug-in that serves it. */
    public record RegisteredProvider(ProviderDefinition definition, ProviderConnector connector) {
        public String id() {
            return definition.getId();
        }
    }

    private final ProviderHubProperties properties;
    private final Map<String, ProviderConnector> pluginsByType = new LinkedHashMap<>();
    private final Map<String, RegisteredProvider> enabled = new LinkedHashMap<>();

    public ProviderRegistry(ProviderHubProperties properties, List<ProviderConnector> installedPlugins) {
        this.properties = properties;
        properties.assignIds();
        for (ProviderConnector plugin : installedPlugins) {
            if (pluginsByType.putIfAbsent(plugin.type(), plugin) != null) {
                throw new IllegalStateException("Two plug-ins claim connector type " + plugin.type());
            }
        }
        for (ProviderDefinition definition : properties.getProviders().values()) {
            if (!definition.isEnabled()) {
                log.info("Provider {} is configured but disabled", definition.getId());
                continue;
            }
            ProviderConnector plugin = pluginsByType.get(definition.getConnector());
            if (plugin == null) {
                throw new IllegalStateException("Provider " + definition.getId() + " needs connector type '"
                        + definition.getConnector() + "' but no such plug-in is installed. Installed: "
                        + pluginsByType.keySet());
            }
            enabled.put(definition.getId(), new RegisteredProvider(definition, plugin));
            log.info("Provider {} plugged in with connector {}", definition.getId(), plugin.type());
        }
    }

    /** The enabled provider with this id, or 404 PROVIDER_NOT_FOUND. */
    public RegisteredProvider require(String providerId) {
        RegisteredProvider provider = providerId == null ? null : enabled.get(providerId.toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw ApiException.notFound("PROVIDER_NOT_FOUND", "No active provider with id " + providerId);
        }
        return provider;
    }

    public Collection<RegisteredProvider> enabledProviders() {
        return List.copyOf(enabled.values());
    }

    public Set<String> installedConnectorTypes() {
        return new TreeSet<>(pluginsByType.keySet());
    }

    /** Every configured provider, enabled or not (M5.1). */
    public List<ProviderView> describeAll() {
        List<ProviderView> views = new ArrayList<>();
        for (ProviderDefinition d : properties.getProviders().values()) {
            views.add(new ProviderView(d.getId(), d.getName(), d.getConnector(),
                    d.getModes().stream().map(Enum::name).toList(), d.isEnabled() ? "ENABLED" : "DISABLED"));
        }
        return views;
    }

    /**
     * Calls a plug-in. A ProviderUnavailableException (provider briefly down) is retried with a short pause
     * when the operation is safe to repeat; after the last attempt the caller gets 503 PROVIDER_UNAVAILABLE.
     */
    public <T> T call(String providerId, String operation, boolean safeToRepeat, Supplier<T> action) {
        int attempts = safeToRepeat ? Math.max(1, properties.getConnectorRetries()) : 1;
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (ProviderUnavailableException e) {
                log.warn("{} {} failed (attempt {}/{}): {}", providerId, operation, attempt, attempts, e.getMessage());
                if (attempt >= attempts) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                            providerId + " could not " + operation + ": " + e.getMessage());
                }
                pause(properties.getRetryBackoffMs() * attempt);
            }
        }
    }

    public void run(String providerId, String operation, boolean safeToRepeat, Runnable action) {
        call(providerId, operation, safeToRepeat, () -> {
            action.run();
            return null;
        });
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
