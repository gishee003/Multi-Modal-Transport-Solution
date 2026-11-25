package com.mmts.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Proof of concept: the API gateway of the deployment (Kong on VM-1 / VM-2 in production).
 *
 * Every call from a front end enters here. The gateway checks the login token once ({@link TokenCheckFilter}),
 * gives each request an id, and routes it by path to the service that owns it. The services never see a call
 * without a valid token, and the front ends need to know only this one address.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder routes,
                        @Value("${mmts.routes.identity}") String identity,
                        @Value("${mmts.routes.journey}") String journey,
                        @Value("${mmts.routes.booking}") String booking,
                        @Value("${mmts.routes.payment}") String payment,
                        @Value("${mmts.routes.hub}") String hub,
                        @Value("${mmts.routes.contracts}") String contracts) {
        return routes.routes()
                // APP-01 Traveller
                .route("identity", r -> r.path("/api/v1/auth/**", "/api/v1/users/**", "/api/v1/licences/**").uri(identity))
                .route("journey", r -> r.path("/api/v1/journeys/**", "/api/v1/trips/**").uri(journey))
                .route("booking", r -> r.path("/api/v1/bookings/**").uri(booking))
                .route("payment", r -> r.path("/api/v1/payments/**", "/api/v1/wallets/**", "/api/v1/finance/**",
                        "/api/v1/ledger/**", "/api/v1/payouts/**").uri(payment))
                // APP-02 TSP Partner
                .route("tsp-hub", r -> r.path("/api/v1/holds/**", "/api/v1/vehicles/**", "/api/v1/providers/**",
                        "/api/v1/telemetry/**", "/api/v1/drivers/**").uri(hub))
                .route("contracts", r -> r.path("/api/v1/tsps/**", "/api/v1/contracts/**", "/api/v1/sla/**").uri(contracts))
                .build();
    }
}
