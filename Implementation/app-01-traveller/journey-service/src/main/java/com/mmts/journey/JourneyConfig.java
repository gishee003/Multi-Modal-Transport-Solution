package com.mmts.journey;

import com.mmts.journey.domain.FareRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Settings for this application:
 * - reads the fares from application.yml into {@link FareRules};
 * - switches on timers (@Scheduled: vehicle simulator, live status, cache expiry) unless
 *   mmts.journey.scheduler.enabled=false, which the tests use so timers never disturb them.
 */
@Configuration
@EnableConfigurationProperties(FareRules.class)
public class JourneyConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "mmts.journey.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Timers {
    }
}
