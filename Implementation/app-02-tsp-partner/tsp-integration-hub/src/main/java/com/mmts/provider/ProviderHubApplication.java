package com.mmts.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Start-up class. Run with: mvn spring-boot:run
 * Login is by token only, so Spring's default username/password user is switched off.
 * ConfigurationPropertiesScan picks up ProviderHubProperties (the mmts.provider-hub settings).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ProviderHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderHubApplication.class, args);
    }
}
