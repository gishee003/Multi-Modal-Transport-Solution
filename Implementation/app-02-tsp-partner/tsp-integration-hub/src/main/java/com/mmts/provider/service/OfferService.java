package com.mmts.provider.service;

import com.mmts.provider.api.dto.DriverDtos.OfferView;
import com.mmts.provider.common.error.ApiException;
import com.mmts.provider.domain.Place;
import com.mmts.provider.domain.RideOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ride offers to drivers (M5.6, INT-08), pushed with Server-Sent Events (SSE).
 *
 * SSE: the TSP staff app opens one long HTTP request (GET .../offers, "text/event-stream") and the server writes a
 * small text event down it whenever something happens. This is the Observer pattern: each open stream is a
 * subscriber of "offers for driver X". Production uses WebSocket (two-way); SSE is the one-way equivalent.
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final Map<String, RideOffer> offers = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ProviderHubProperties properties;
    private final Clock clock;

    public OfferService(ProviderHubProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Opens a stream for one driver and immediately sends any offers still waiting for an answer. */
    public SseEmitter subscribe(String driverId) {
        SseEmitter emitter = new SseEmitter(properties.getOfferStreamTimeoutMs());
        List<SseEmitter> list = streams.computeIfAbsent(driverId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected to ride offers of " + driverId));
            for (RideOffer offer : offers.values()) {
                if (offer.getDriverId().equals(driverId) && offer.isPending()) {
                    emitter.send(SseEmitter.event().name("ride-offer").id(offer.getOfferId())
                            .data(view(offer), MediaType.APPLICATION_JSON));
                }
            }
        } catch (IOException e) {
            list.remove(emitter);
        }
        return emitter;
    }

    public OfferView create(String driverId, String holdId, Place pickup, Place drop, long farePaise) {
        RideOffer offer = new RideOffer("of-" + UUID.randomUUID().toString().substring(0, 8), driverId, holdId,
                pickup, drop, farePaise, clock.instant().truncatedTo(ChronoUnit.SECONDS));
        offers.put(offer.getOfferId(), offer);
        push(driverId, "ride-offer", offer);
        return view(offer);
    }

    /** Driver's answer. 404 OFFER_NOT_FOUND (also for another driver's offer), 409 OFFER_NOT_PENDING. */
    public OfferView respond(String driverId, String offerId, boolean accept) {
        RideOffer offer = offers.get(offerId);
        if (offer == null || !offer.getDriverId().equals(driverId)) {
            throw ApiException.notFound("OFFER_NOT_FOUND", "No offer " + offerId + " for driver " + driverId);
        }
        synchronized (offer) {
            if (!offer.isPending()) {
                throw ApiException.conflict("OFFER_NOT_PENDING", "Offer " + offerId + " is already " + offer.getStatus());
            }
            offer.setStatus(accept ? RideOffer.Status.ACCEPTED : RideOffer.Status.REJECTED);
        }
        return view(offer);
    }

    /** The hold was released: cancel its offer and tell the driver. Returns the driver who had it. */
    public Optional<String> cancelForHold(String holdId) {
        for (RideOffer offer : offers.values()) {
            if (offer.getHoldId().equals(holdId) && offer.getStatus() != RideOffer.Status.CANCELLED) {
                offer.setStatus(RideOffer.Status.CANCELLED);
                push(offer.getDriverId(), "offer-cancelled", offer);
                return Optional.of(offer.getDriverId());
            }
        }
        return Optional.empty();
    }

    private void push(String driverId, String eventName, RideOffer offer) {
        for (SseEmitter emitter : streams.getOrDefault(driverId, List.of())) {
            try {
                emitter.send(SseEmitter.event().name(eventName).id(offer.getOfferId())
                        .data(view(offer), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("Stream of driver {} closed: {}", driverId, e.getMessage());
                streams.get(driverId).remove(emitter);
            }
        }
    }

    private static OfferView view(RideOffer o) {
        return new OfferView(o.getOfferId(), o.getDriverId(), o.getHoldId(), o.getPickup(), o.getDrop(),
                o.getFarePaise(), o.getStatus().name(), o.getCreatedAt());
    }
}
