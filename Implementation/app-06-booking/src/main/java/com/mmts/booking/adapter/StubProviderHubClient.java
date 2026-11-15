package com.mmts.booking.adapter;

import com.mmts.booking.BookingProperties;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.Mode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for APP-08. Behaves like INT-12:
 * - knows the providers and the fare per mode listed in application.yml (mmts.booking.stub);
 * - confirm returns a ticket reference (metro / bus), an unlock code (scooter / bike / car) or an assigned driver (taxi);
 * - release is safe to repeat; unlock needs a confirmed hold.
 * The demo can make one provider refuse holds, or make the next confirm fail.
 */
@Component
public class StubProviderHubClient implements ProviderHubClient {

    private static final List<Driver> DRIVERS = List.of(
            new Driver("d-21", "Ramesh Kumar", "DL 1C AB 4321", 6),
            new Driver("d-22", "Sunita Yadav", "DL 3C CD 9087", 4));

    private final Clock clock;
    private final BookingProperties properties;
    private final AtomicLong holdSeq = new AtomicLong(300);
    private final AtomicLong codeSeq = new AtomicLong(7730);
    private final Map<String, HoldRecord> holds = new ConcurrentHashMap<>();
    private final Set<String> refusingProviders = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean failNextConfirm = new AtomicBoolean(false);

    public StubProviderHubClient(Clock clock, BookingProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public HoldResult hold(HoldRequest request) {
        if (!properties.stub().providers().contains(request.providerId())) {
            throw ApiException.notFound("PROVIDER_NOT_FOUND", "Unknown provider " + request.providerId());
        }
        if (refusingProviders.contains(request.providerId())) {
            throw ApiException.conflict("NOT_AVAILABLE",
                    request.providerId() + " has no seat or vehicle for leg " + request.legId());
        }
        Mode mode = Mode.valueOf(request.mode());
        String holdId = "h-" + holdSeq.incrementAndGet();
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(properties.holdTimeoutMinutes()));
        holds.put(holdId, new HoldRecord(request, mode, "HELD"));
        long price = properties.stub().faresPaise().getOrDefault(mode, 0L);
        return new HoldResult(holdId, request.legId(), request.providerId(), "HELD", price, expiresAt);
    }

    @Override
    public ConfirmResult confirm(String holdId) {
        HoldRecord hold = holds.get(holdId);
        if (hold == null || !"HELD".equals(hold.status) || failNextConfirm.getAndSet(false)) {
            throw ApiException.conflict("HOLD_NOT_ACTIVE", "Hold " + holdId + " is no longer active");
        }
        hold.status = "CONFIRMED";
        Instant start = hold.request.startTime();
        String provider = hold.request.providerId();
        if (hold.mode.usesQrTicket()) {
            String ticketRef = provider + "-T-" + holdId.substring(2);
            return new ConfirmResult(holdId, "CONFIRMED",
                    new Access("QR_TICKET", ticketRef, clock.instant(), start.plus(Duration.ofHours(3))), null);
        }
        if (hold.mode.usesUnlockCode()) {
            String code = provider.substring(0, 2) + "-" + codeSeq.incrementAndGet();
            return new ConfirmResult(holdId, "CONFIRMED",
                    new Access("UNLOCK_CODE", code, start.minus(Duration.ofMinutes(5)), start.plus(Duration.ofMinutes(55))),
                    null);
        }
        Driver driver = DRIVERS.get((int) (codeSeq.incrementAndGet() % DRIVERS.size()));
        return new ConfirmResult(holdId, "CONFIRMED",
                new Access("DRIVER_ASSIGNED", driver.driverId(), start, start.plus(Duration.ofHours(1))), driver);
    }

    @Override
    public void release(String holdId) {
        HoldRecord hold = holds.get(holdId);
        if (hold != null) {
            hold.status = "RELEASED";      // releasing again is fine: still RELEASED
        }
    }

    @Override
    public UnlockResult unlock(String vehicleId, String holdId, String userId) {
        HoldRecord hold = holds.get(holdId);
        if (hold == null || !"CONFIRMED".equals(hold.status)) {
            throw ApiException.conflict("HOLD_NOT_CONFIRMED", "Hold " + holdId + " is not confirmed");
        }
        return new UnlockResult(vehicleId, "UNLOCKED", clock.instant());
    }

    /** For tests and the demo: HELD, CONFIRMED or RELEASED (null if unknown). */
    public String statusOf(String holdId) {
        HoldRecord hold = holds.get(holdId);
        return hold == null ? null : hold.status;
    }

    /** Demo control: this provider now answers every hold with 409 NOT_AVAILABLE. */
    public void refuseHoldsFrom(String providerId) {
        refusingProviders.add(providerId);
    }

    /** Demo control: the next confirm fails with 409 HOLD_NOT_ACTIVE. */
    public void failNextConfirm() {
        failNextConfirm.set(true);
    }

    public Set<String> refusingProviders() {
        return Set.copyOf(refusingProviders);
    }

    public boolean confirmWillFail() {
        return failNextConfirm.get();
    }

    public void reset() {
        refusingProviders.clear();
        failNextConfirm.set(false);
    }

    /** What the stub remembers about one hold. */
    private static final class HoldRecord {
        private final HoldRequest request;
        private final Mode mode;
        private volatile String status;

        private HoldRecord(HoldRequest request, Mode mode, String status) {
            this.request = request;
            this.mode = mode;
            this.status = status;
        }
    }
}
