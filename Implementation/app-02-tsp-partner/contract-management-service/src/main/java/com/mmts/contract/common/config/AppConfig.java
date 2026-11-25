package com.mmts.contract.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Shared configuration:
 * - Clock: one source of "now", so tests can control time.
 * - Caching: @Cacheable works; in-memory in the demo, Redis in production (same annotation).
 * - Swagger UI: shows a "Authorize" button for the Bearer token.
 */
@Configuration
@EnableCaching
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI openApi(@Value("${spring.application.name}") String name, @Value("${mmts.app-id}") String appId) {
        return new OpenAPI()
                .info(new Info().title(appId + " " + name).version("1.0.0")
                        .description("MMTS. Get a test token from POST /dev/token, then press Authorize."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
