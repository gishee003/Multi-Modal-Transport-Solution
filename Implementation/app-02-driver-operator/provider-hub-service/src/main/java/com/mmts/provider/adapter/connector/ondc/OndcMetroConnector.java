package com.mmts.provider.adapter.connector.ondc;

import com.mmts.provider.adapter.connector.ProviderConnector;
import com.mmts.provider.adapter.connector.ondc.StubOndcNetwork.BecknMessage;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.domain.ProviderDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Plug-in 1: metro / bus QR tickets through the ONDC network (Beckn protocol).
 *
 * hold    = select (get a quote) then init (draft order)   → waits for on_select and on_init
 * confirm = confirm                                        → waits for on_confirm, which carries the QR ticket
 * release = cancel                                         → waits for on_cancel
 *
 * Each request gets a messageId. We park a "future" under that id and wait (with a timeout from
 * application.yml) until the matching on_* callback completes it. No answer in time = 504 PROVIDER_TIMEOUT.
 */
@Component
public class OndcMetroConnector implements ProviderConnector {

    private static final Logger log = LoggerFactory.getLogger(OndcMetroConnector.class);

    private final StubOndcNetwork network;
    private final Map<String, CompletableFuture<BecknMessage>> waitingForCallback = new ConcurrentHashMap<>();

    public OndcMetroConnector(StubOndcNetwork network) {
        this.network = network;
    }

    @Override
    public String type() {
        return "ondc-metro";
    }

    @Override
    public HoldQuote hold(ProviderDefinition provider, LegRequest leg) {
        String transactionId = "txn-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> journey = new HashMap<>();
        journey.put("fromStopId", leg.from().stopId());
        journey.put("toStopId", leg.to().stopId());
        journey.put("fromLat", leg.from().lat());
        journey.put("fromLon", leg.from().lon());
        journey.put("toLat", leg.to().lat());
        journey.put("toLon", leg.to().lon());

        BecknMessage onSelect = request(provider, "select", transactionId, journey);
        long price = ((Number) onSelect.body().get("pricePaise")).longValue();
        journey.put("pricePaise", price);
        request(provider, "init", transactionId, journey);
        return new HoldQuote(price, null, transactionId);
    }

    @Override
    public AccessGrant confirm(ProviderDefinition provider, LegRequest leg, String transactionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromStopId", leg.from().stopId());
        body.put("toStopId", leg.to().stopId());
        BecknMessage onConfirm = request(provider, "confirm", transactionId, body);
        String qr = (String) onConfirm.body().get("ticketQr");
        return AccessGrant.of("QR_TICKET", qr, leg.startTime(), provider.intSetting("ticket-valid-minutes", 120), null);
    }

    @Override
    public void release(ProviderDefinition provider, LegRequest leg, String transactionId) {
        request(provider, "cancel", transactionId, Map.of());
    }

    /**
     * The callback URL. In production the ONDC network POSTs on_select, on_init ... to an endpoint of ours;
     * here the stub network calls this method directly.
     */
    public void onCallback(BecknMessage reply) {
        CompletableFuture<BecknMessage> waiting = waitingForCallback.get(reply.messageId());
        if (waiting == null) {
            log.warn("Late or unknown ONDC callback {} for message {}", reply.action(), reply.messageId());
            return;
        }
        waiting.complete(reply);
    }

    /** Send one Beckn request and wait for its on_* callback. */
    private BecknMessage request(ProviderDefinition provider, String action, String transactionId,
                                 Map<String, Object> body) {
        String messageId = UUID.randomUUID().toString();
        CompletableFuture<BecknMessage> answer = new CompletableFuture<>();
        waitingForCallback.put(messageId, answer);
        long timeoutMs = provider.intSetting("callback-timeout-ms", 2000);
        try {
            String ack = network.send(new BecknMessage(action, transactionId, messageId, body), this::onCallback);
            if (!"ACK".equals(ack)) {
                throw new ProviderUnavailableException("ONDC refused " + action + ": " + ack);
            }
            BecknMessage reply = answer.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!("on_" + action).equals(reply.action())) {
                throw new ProviderUnavailableException("Expected on_" + action + " but got " + reply.action());
            }
            return reply;
        } catch (TimeoutException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT",
                    provider.getId() + " sent no on_" + action + " callback within " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while waiting for on_" + action);
        } catch (ExecutionException e) {
            throw new ProviderUnavailableException("ONDC call failed: " + e.getMessage());
        } finally {
            waitingForCallback.remove(messageId);
        }
    }
}
