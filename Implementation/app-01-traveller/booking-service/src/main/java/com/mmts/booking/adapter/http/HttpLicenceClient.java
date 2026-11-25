package com.mmts.booking.adapter.http;

import com.mmts.booking.adapter.LicenceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Proof of concept: INT-10 as a real call to the Identity service (replaces StubLicenceClient). */
@Component
@Primary
@Profile("poc")
public class HttpLicenceClient implements LicenceClient {

    private final RemoteCalls remote;
    private final String baseUrl;

    public HttpLicenceClient(RemoteCalls remote, @Value("${mmts.poc.identity-url}") String baseUrl) {
        this.remote = remote;
        this.baseUrl = baseUrl;
    }

    @Override
    public DriveEligibility checkDriveEligibility(String userId, String vehicleClass, String purpose) {
        return remote.get(baseUrl + "/api/v1/users/" + userId + "/drive-eligibility?vehicleClass=" + vehicleClass
                + "&purpose=" + purpose, DriveEligibility.class);
    }
}
