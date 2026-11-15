package com.mmts.journey;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Start-up class. Run with: mvn spring-boot:run
 * Login is by token only, so Spring's default username/password user is switched off.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class JourneyApplication {

    public static void main(String[] args) {
        SpringApplication.run(JourneyApplication.class, args);
    }
}
