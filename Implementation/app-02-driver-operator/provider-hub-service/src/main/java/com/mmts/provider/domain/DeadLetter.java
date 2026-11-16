package com.mmts.provider.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A reading the hub could not accept (bad coordinates, unknown provider ...).
 * It is kept here with the reason instead of being thrown away, so nothing is silently lost (driver D11).
 */
@Entity
@Table(name = "dead_letters")
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String source;        // TELEMETRY_API, DRIVER_APP, FEED:<providerId>, DEMO
    private String providerId;
    private String vehicleId;
    private String reason;        // e.g. INVALID_COORDINATES

    @Column(length = 2000)
    private String rawReading;    // the reading exactly as received, as JSON

    private Instant receivedAt;

    protected DeadLetter() {
        // for JPA
    }

    public DeadLetter(String source, String providerId, String vehicleId, String reason,
                      String rawReading, Instant receivedAt) {
        this.source = source;
        this.providerId = providerId;
        this.vehicleId = vehicleId;
        this.reason = reason;
        this.rawReading = rawReading;
        this.receivedAt = receivedAt;
    }

    /** Ids are strings in every MMTS contract, so the number is shown as "dl-12". */
    public String publicId() {
        return "dl-" + id;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getProviderId() { return providerId; }
    public String getVehicleId() { return vehicleId; }
    public String getReason() { return reason; }
    public String getRawReading() { return rawReading; }
    public Instant getReceivedAt() { return receivedAt; }
}
