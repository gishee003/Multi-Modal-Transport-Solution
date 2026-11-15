package com.mmts.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Start-up class. Run with: mvn spring-boot:run
 * Login is by token only, so Spring's default username/password user is switched off.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ProviderHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderHubApplication.class, args);
    }
}
