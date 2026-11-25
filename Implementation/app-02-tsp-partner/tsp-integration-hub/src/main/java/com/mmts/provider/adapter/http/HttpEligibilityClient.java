package com.mmts.provider.adapter.http;

import com.mmts.provider.adapter.eligibility.EligibilityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Proof of concept: INT-13 as a real call to the Identity service in APP-01 (replaces StubEligibilityClient). */
@Component
@Primary
@Profile("poc")
public class HttpEligibilityClient implements EligibilityClient {

    private final RemoteCalls remote;
    private final String baseUrl;

    public HttpEligibilityClient(RemoteCalls remote, @Value("${mmts.poc.identity-url}") String baseUrl) {
        this.remote = remote;
        this.baseUrl = baseUrl;
    }

    @Override
    public Eligibility check(String userId, String vehicleClass, String purpose) {
        return remote.get(baseUrl + "/api/v1/users/" + userId + "/drive-eligibility?vehicleClass=" + vehicleClass
                + "&purpose=" + purpose, Eligibility.class);
    }
}
