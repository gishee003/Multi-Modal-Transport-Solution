package com.mmts.booking.adapter.http;

import com.mmts.booking.adapter.ProviderHubClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Proof of concept: INT-12 as real calls to the TSP Integration Hub in APP-02 (replaces StubProviderHubClient). */
@Component
@Primary
@Profile("poc")
public class HttpProviderHubClient implements ProviderHubClient {

    private final RemoteCalls remote;
    private final String baseUrl;

    public HttpProviderHubClient(RemoteCalls remote, @Value("${mmts.poc.hub-url}") String baseUrl) {
        this.remote = remote;
        this.baseUrl = baseUrl;
    }

    @Override
    public HoldResult hold(HoldRequest request) {
        return remote.post(baseUrl + "/api/v1/holds", request, null, HoldResult.class);
    }

    @Override
    public ConfirmResult confirm(String holdId) {
        return remote.post(baseUrl + "/api/v1/holds/" + holdId + "/confirm", null, null, ConfirmResult.class);
    }

    @Override
    public void release(String holdId) {
        remote.delete(baseUrl + "/api/v1/holds/" + holdId);
    }

    @Override
    public UnlockResult unlock(String vehicleId, String holdId, String userId) {
        return remote.post(baseUrl + "/api/v1/vehicles/" + vehicleId + "/unlock",
                Map.of("holdId", holdId, "userId", userId), null, UnlockResult.class);
    }
}
