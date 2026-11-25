package com.mmts.provider.service;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.adapter.connector.ProviderConnector.ProviderUnavailableException;
import com.mmts.provider.adapter.connector.bike.BikeShareConnector;
import com.mmts.provider.adapter.connector.gbfs.ScooterGbfsConnector;
import com.mmts.provider.adapter.connector.gtfs.GtfsBusConnector;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.domain.Mode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.mmts.provider.support.TestProviders.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The microkernel core: registry / factory, configuration-only plug-in, retries (driver D10). */
class ProviderRegistryTest {

    private static final List<ProviderConnector> INSTALLED =
            List.of(new ScooterGbfsConnector(), new GtfsBusConnector(), new BikeShareConnector());

    private static ProviderHubProperties config(boolean hopbikeEnabled) {
        ProviderHubProperties p = new ProviderHubProperties();
        p.setRetryBackoffMs(1);
        p.getProviders().put("ZIPRIDE", provider("ZIPRIDE", "gbfs-scooter", Mode.SCOOTER, true));
        p.getProviders().put("DTC", provider("DTC", "gtfs-bus", Mode.BUS, true));
        p.getProviders().put("HOPBIKE", provider("HOPBIKE", "gbfs-bike", Mode.BIKE, hopbikeEnabled));
        return p;
    }

    @Test
    void registryPicksTheConnectorNamedInConfiguration() {
        ProviderRegistry registry = new ProviderRegistry(config(false), INSTALLED);

        assertThat(registry.require("ZIPRIDE").connector()).isInstanceOf(ScooterGbfsConnector.class);
        assertThat(registry.require("dtc").connector()).isInstanceOf(GtfsBusConnector.class);   // any letter case
    }

    @Test
    void unknownProviderIs404ProviderNotFound() {
        ProviderRegistry registry = new ProviderRegistry(config(false), INSTALLED);

        assertThatThrownBy(() -> registry.require("NOPE"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PROVIDER_NOT_FOUND");
                    assertThat(e.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void disabledProviderIsAbsentThenPresentAfterConfigurationChange() {
        ProviderRegistry before = new ProviderRegistry(config(false), INSTALLED);
        assertThatThrownBy(() -> before.require("HOPBIKE")).isInstanceOf(ApiException.class);
        assertThat(before.describeAll()).anyMatch(v -> v.providerId().equals("HOPBIKE") && v.status().equals("DISABLED"));

        // Only the configuration changed: same plug-in list, same core classes.
        ProviderRegistry after = new ProviderRegistry(config(true), INSTALLED);
        assertThat(after.require("HOPBIKE").connector()).isInstanceOf(BikeShareConnector.class);
        assertThat(after.describeAll()).anyMatch(v -> v.providerId().equals("HOPBIKE") && v.status().equals("ENABLED"));
    }

    @Test
    void providerNeedingAMissingPluginStopsStartUpWithAClearMessage() {
        ProviderHubProperties p = config(false);
        p.getProviders().put("MYSTERY", provider("MYSTERY", "teleporter", Mode.CAR, true));

        assertThatThrownBy(() -> new ProviderRegistry(p, INSTALLED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teleporter");
    }

    @Test
    void safeCallsAreRetriedUntilTheProviderAnswers() {
        ProviderRegistry registry = new ProviderRegistry(config(false), INSTALLED);
        AtomicInteger attempts = new AtomicInteger();

        String result = registry.call("ZIPRIDE", "release", true, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ProviderUnavailableException("timeout");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void unsafeCallsAreTriedOnceAndGive503() {
        ProviderRegistry registry = new ProviderRegistry(config(false), INSTALLED);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> registry.call("ZIPRIDE", "hold", false, () -> {
            attempts.incrementAndGet();
            throw new ProviderUnavailableException("down");
        })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PROVIDER_UNAVAILABLE"));
        assertThat(attempts).hasValue(1);
    }

    /** D10 proof in the source code: nothing outside the plug-in folders names a concrete plug-in class. */
    @Test
    void coreCodeNeverNamesAConcretePlugin() throws IOException {
        Path main = Path.of("src/main/java/com/mmts/provider");
        Path pluginRoot = main.resolve("adapter/connector");
        List<String> pluginClasses;
        try (Stream<Path> files = Files.walk(pluginRoot)) {
            pluginClasses = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getParent().equals(pluginRoot))          // only files in plug-in sub-folders
                    .map(f -> f.getFileName().toString().replace(".java", ""))
                    .toList();
        }
        assertThat(pluginClasses).contains("OndcMetroConnector", "GtfsBusConnector", "ScooterGbfsConnector",
                "CarRentalConnector", "TaxiDriverConnector", "BikeShareConnector");

        try (Stream<Path> files = Files.walk(main)) {
            List<Path> coreFiles = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.startsWith(pluginRoot) || f.getParent().equals(pluginRoot))
                    .toList();
            for (Path file : coreFiles) {
                String source = Files.readString(file);
                for (String plugin : pluginClasses) {
                    assertThat(source).as(file + " must not mention " + plugin).doesNotContain(plugin);
                }
            }
        }
    }
}
