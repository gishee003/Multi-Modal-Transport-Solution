package com.mmts.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** M3.7: a problem the traveller reported about a booking (support staff pick it up). */
@Entity
@Table(name = "problem_reports")
public class ProblemReport {

    /** What kind of problem it is. */
    public enum Category { VEHICLE_FAULT, DRIVER_BEHAVIOUR, TICKET_NOT_ACCEPTED, OVERCHARGED, SAFETY, OTHER }

    @Id
    private String id;
    private String bookingId;
    private String legId;
    private String userId;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Column(length = 1000)
    private String description;

    private String status;
    private Instant createdAt;

    protected ProblemReport() {
        // for JPA
    }

    public ProblemReport(String id, String bookingId, String legId, String userId, Category category,
                         String description, Instant createdAt) {
        this.id = id;
        this.bookingId = bookingId;
        this.legId = legId;
        this.userId = userId;
        this.category = category;
        this.description = description;
        this.status = "OPEN";
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public String getLegId() {
        return legId;
    }

    public String getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
