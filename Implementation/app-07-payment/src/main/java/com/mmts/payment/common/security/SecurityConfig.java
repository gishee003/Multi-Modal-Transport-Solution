package com.mmts.payment.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.payment.common.error.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Security rules shared by every MMTS application (the open security layer in Figure 3).
 * - No sessions: every request carries its own token (stateless, scales horizontally, driver D1).
 * - Public: health, Swagger, H2 console and demo helpers. Everything else needs a valid token.
 * - Finer role checks are done with @PreAuthorize on controller methods.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
            "/h2-console/**",
            "/dev/**",
            "/error"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                            ObjectMapper objectMapper, Clock clock) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))   // lets the H2 console open in the browser
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            Object reason = request.getAttribute("mmts.authError");
                            write(response, objectMapper, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                                    reason != null ? reason.toString() : "A valid login token is required", clock);
                        })
                        .accessDeniedHandler((request, response, e) ->
                                write(response, objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "Your role is not allowed to do this", clock)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatus status,
                              String code, String message, Clock clock) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, List.of(), clock.instant()));
    }
}
