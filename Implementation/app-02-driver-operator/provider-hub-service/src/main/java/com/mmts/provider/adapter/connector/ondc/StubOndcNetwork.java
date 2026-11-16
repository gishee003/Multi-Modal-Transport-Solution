package com.mmts.provider.adapter.connector.ondc;

import com.mmts.provider.domain.Place;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stub of the ONDC network and DMRC's ticketing system behind it.
 *
 * ONDC uses the Beckn protocol, which is ASYNCHRONOUS: the buyer app (us, the "BAP") sends a request such as
 * "select"; the network only answers "ACK" at once. A little later the seller app (DMRC, the "BPP") calls the
 * buyer's callback URL with the real answer, "on_select". The same pair exists for init / on_init,
 * confirm / on_confirm and cancel / on_cancel. This stub keeps exactly that shape: {@link #send} returns
 * "ACK" immediately and the answer arrives later on another thread through the callback.
 */
@Component
public class StubOndcNetwork {

    /** One Beckn message (request or callback). transactionId ties a whole order together. */
    public record BecknMessage(String action, String transactionId, String messageId, Map<String, Object> body) {
    }

    private final ScheduledExecutorService bpp = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ondc-bpp-stub");
        t.setDaemon(true);
        return t;
    });
    private final List<String> messageLog = new CopyOnWriteArrayList<>();
    private volatile long callbackDelayMs = 30;
    private volatile boolean silent = false;     // true = the BPP never answers (to show timeouts)

    /** Sends a request into the network. Returns "ACK" now; the answer comes later through the callback. */
    public String send(BecknMessage request, Consumer<BecknMessage> callbackUrl) {
        messageLog.add(request.action());
        if (!silent) {
            bpp.schedule(() -> {
                BecknMessage reply = answer(request);
                messageLog.add(reply.action());
                callbackUrl.accept(reply);
            }, callbackDelayMs, TimeUnit.MILLISECONDS);
        }
        return "ACK";
    }

    /** What DMRC's seller app would answer. */
    private BecknMessage answer(BecknMessage request) {
        Map<String, Object> body = new HashMap<>();
        switch (request.action()) {
            case "select" -> body.put("pricePaise", dmrcFarePaise(request.body()));
            case "init" -> body.put("orderId", "DMRC-ORD-" + shortId());
            case "confirm" -> body.put("ticketQr", "ONDC|DMRC|" + request.transactionId() + "|"
                    + request.body().get("fromStopId") + ">" + request.body().get("toStopId") + "|" + shortId());
            case "cancel" -> body.put("state", "CANCELLED");
            default -> body.put("error", "UNKNOWN_ACTION");
        }
        return new BecknMessage("on_" + request.action(), request.transactionId(), request.messageId(), body);
    }

    /** DMRC fares by distance slab (2026 fare chart, rupees 11 to 64), in paise. */
    static long dmrcFarePaise(Map<String, Object> body) {
        double km = Place.distanceKm((double) body.get("fromLat"), (double) body.get("fromLon"),
                (double) body.get("toLat"), (double) body.get("toLon"));
        if (km <= 2) return 1100;
        if (km <= 5) return 2100;
        if (km <= 12) return 3200;
        if (km <= 21) return 4300;
        if (km <= 32) return 5400;
        return 6400;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Every request and callback seen, in order (e.g. select, on_select, init, on_init). */
    public List<String> messageLog() {
        return List.copyOf(messageLog);
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    public void setCallbackDelayMs(long callbackDelayMs) {
        this.callbackDelayMs = callbackDelayMs;
    }

    @PreDestroy
    void shutdown() {
        bpp.shutdownNow();
    }
}
