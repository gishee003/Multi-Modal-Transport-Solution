package com.mmts.identity.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Settings that only APP-04 needs (the shared ones are in common/, which stays identical in every app).
 */
@Configuration
@EnableScheduling   // switches on the nightly @Scheduled licence expiry check
public class IdentityConfig {

    /** BCrypt: a slow, salted one-way hash for passwords and OTP codes. */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${mmts.identity.bcrypt-strength}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    /**
     * The background worker that talks to DigiLocker, so a slow DigiLocker never slows a request (driver D1).
     * Tests set mmts.identity.verification.async=false to run the check straight away, which makes them repeatable.
     */
    @Bean("licenceVerificationExecutor")
    TaskExecutor licenceVerificationExecutor(@Value("${mmts.identity.verification.async}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("licence-check-");
        executor.initialize();
        return executor;
    }

    /**
     * Register, login and "send me an OTP" must work without a token (you do not have one yet).
     * The shared SecurityConfig protects every /api/** path, so this extra rule, checked first (@Order(1)),
     * opens only /api/v1/auth/**. Everything else still goes through the shared rules.
     */
    @Bean
    @Order(1)
    SecurityFilterChain publicAuthEndpoints(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/auth/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
