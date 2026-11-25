package com.mmts.identity.domain;

import com.mmts.identity.common.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One MMTS account (traveller, driver or staff).
 * Personal data: name, mobile, email, saved places. {@link #anonymise(Instant)} removes all of it (driver D2).
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private String id;

    private String fullName;

    @Column(unique = true)
    private String mobile;

    @Column(unique = true)
    private String email;

    private String passwordHash;   // BCrypt hash, never the password itself

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_saved_place", joinColumns = @JoinColumn(name = "user_id"))
    private List<SavedPlace> savedPlaces = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private AccountStatus status = AccountStatus.ACTIVE;

    private boolean licenceCheckConsent;
    private Instant consentRecordedAt;
    private Instant createdAt;
    private Instant deletedAt;

    protected UserAccount() {
        // for JPA
    }

    public UserAccount(String id, String fullName, String mobile, String email, String passwordHash,
                       Set<Role> roles, Instant createdAt) {
        this.id = id;
        this.fullName = fullName;
        this.mobile = mobile;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = EnumSet.copyOf(roles);
        this.createdAt = createdAt;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public void updateProfile(String fullName, String email) {
        this.fullName = fullName;
        this.email = email;
    }

    public void replaceSavedPlaces(List<SavedPlace> places) {
        savedPlaces.clear();
        savedPlaces.addAll(places);
    }

    /** Records (or withdraws) the user's consent that MMTS may fetch their licence from DigiLocker. */
    public void recordLicenceConsent(boolean given, Instant now) {
        this.licenceCheckConsent = given;
        this.consentRecordedAt = now;
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public void removeRole(Role role) {
        roles.remove(role);
    }

    /**
     * "Delete my data" (driver D2): removes every personal field. Only the id stays, so other
     * applications that stored this id (bookings, payments) do not break, but it no longer points to a person.
     */
    public void anonymise(Instant now) {
        this.fullName = null;
        this.mobile = null;
        this.email = null;
        this.passwordHash = null;
        this.savedPlaces.clear();
        this.roles.clear();
        this.licenceCheckConsent = false;
        this.consentRecordedAt = null;
        this.status = AccountStatus.DELETED;
        this.deletedAt = now;
    }

    public String getId() { return id; }
    public String getFullName() { return fullName; }
    public String getMobile() { return mobile; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Set<Role> getRoles() { return roles; }
    public List<SavedPlace> getSavedPlaces() { return savedPlaces; }
    public AccountStatus getStatus() { return status; }
    public boolean isLicenceCheckConsent() { return licenceCheckConsent; }
    public Instant getConsentRecordedAt() { return consentRecordedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
