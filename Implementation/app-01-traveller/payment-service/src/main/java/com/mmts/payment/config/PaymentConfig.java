package com.mmts.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Settings of this application (on top of the shared ones in common/).
 */
@Configuration
@EnableConfigurationProperties(PaymentSettings.class)
public class PaymentConfig {

    /**
     * The gateway's webhook cannot carry an MMTS login token, so /api/v1/webhooks/** gets its own small security
     * chain that lets the request in. It is NOT unprotected: WebhookService accepts it only with a valid
     * HMAC-SHA256 signature. This chain is checked first (@Order(1)); every other path still uses the shared chain.
     */
    @Bean
    @Order(1)
    SecurityFilterChain webhookSecurityChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Turns on the daily @Scheduled jobs (payout run, settlement file). Tests switch this off with
     * mmts.payment.schedule.enabled=false so no job can run in the middle of a test.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "mmts.payment.schedule.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
