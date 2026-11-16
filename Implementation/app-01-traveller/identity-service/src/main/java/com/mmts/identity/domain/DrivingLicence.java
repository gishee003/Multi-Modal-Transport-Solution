package com.mmts.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A user's driving licence and its verification state (one licence per user).
 * The licence number is stored encrypted (AES-GCM, see {@link EncryptedStringConverter}), driver D2.
 * The eligibility rule (driver D3) lives here, in {@link #eligibilityFor(VehicleClass, LocalDate)}.
 */
@Entity
@Table(name = "driving_licence")
public class DrivingLicence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String userId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 512)
    private String licenceNumber;

    /** Needed only for the DigiLocker lookup; cleared once verification has finished (data minimisation). */
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private LicenceStatus status;

    /** Stored as text such as "LMV,MCWG". */
    private String vehicleClasses = "";

    private LocalDate expiresOn;
    private String rejectionReason;
    private Instant submittedAt;
    private Instant checkedAt;

    protected DrivingLicence() {
        // for JPA
    }

    public DrivingLicence(String userId) {
        this.userId = userId;
    }

    /** A new submission (first time or re-submission after rejection / renewal) starts again at PENDING. */
    public void submit(String licenceNumber, LocalDate dateOfBirth, Instant now) {
        this.licenceNumber = licenceNumber;
        this.dateOfBirth = dateOfBirth;
        this.status = LicenceStatus.PENDING;
        this.vehicleClasses = "";
        this.expiresOn = null;
        this.rejectionReason = null;
        this.submittedAt = now;
        this.checkedAt = null;
    }

    /** DigiLocker returned the licence. It may already be out of date, which makes it EXPIRED instead of VERIFIED. */
    public void markFound(Set<VehicleClass> classes, LocalDate validUntil, LocalDate today, Instant now) {
        this.vehicleClasses = classes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        this.expiresOn = validUntil;
        this.status = validUntil.isBefore(today) ? LicenceStatus.EXPIRED : LicenceStatus.VERIFIED;
        this.dateOfBirth = null;
        this.checkedAt = now;
    }

    public void markRejected(String reason, Instant now) {
        this.status = LicenceStatus.REJECTED;
        this.rejectionReason = reason;
        this.dateOfBirth = null;
        this.checkedAt = now;
    }

    /** Used by the nightly expiry job. */
    public void markExpired(Instant now) {
        this.status = LicenceStatus.EXPIRED;
        this.checkedAt = now;
    }

    /**
     * The drive-eligibility rule (driver D3). The expiry date is also checked live, so a licence that ran out
     * yesterday is refused even before the nightly job has marked it EXPIRED. A licence is valid up to and
     * including its expiry date.
     */
    public EligibilityReason eligibilityFor(VehicleClass vehicleClass, LocalDate today) {
        return switch (status) {
            case PENDING -> EligibilityReason.PENDING_VERIFICATION;
            case REJECTED -> EligibilityReason.REJECTED;
            case EXPIRED -> EligibilityReason.EXPIRED;
            case VERIFIED -> {
                if (expiresOn == null || expiresOn.isBefore(today)) {
                    yield EligibilityReason.EXPIRED;
                }
                yield getVehicleClasses().contains(vehicleClass)
                        ? EligibilityReason.VERIFIED
                        : EligibilityReason.CLASS_NOT_COVERED;
            }
        };
    }

    public Set<VehicleClass> getVehicleClasses() {
        if (vehicleClasses == null || vehicleClasses.isBlank()) {
            return EnumSet.noneOf(VehicleClass.class);
        }
        return Arrays.stream(vehicleClasses.split(","))
                .map(VehicleClass::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(VehicleClass.class)));
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getLicenceNumber() { return licenceNumber; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public LicenceStatus getStatus() { return status; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCheckedAt() { return checkedAt; }
}
