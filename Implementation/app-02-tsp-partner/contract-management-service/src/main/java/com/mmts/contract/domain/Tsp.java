package com.mmts.contract.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A Transport Service Provider registered as a business partner (M7.1).
 * Any organisation that provides a way to travel: a metro operator, a cab fleet, a scooter-sharing company, ...
 * Nothing here depends on the mode: modes are plain values, so a new kind of transport needs no code change.
 */
@Entity
@Table(name = "tsp")
public class Tsp {

    @Id
    private String tspId;

    @Column(nullable = false)
    private String legalName;

    private String registrationNumber;  // company / GST registration (KYC)
    private String bankAccountRef;      // payout account reference at the payment gateway (never the raw number)

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tsp_mode")
    @Column(name = "mode")
    private Set<String> modes = new LinkedHashSet<>();

    /** Users (OPERATOR role) who act for this TSP in the partner portal. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tsp_operator")
    @Column(name = "user_id")
    private Set<String> operators = new LinkedHashSet<>();

    /** KYC / legal documents received (names only; files go to encrypted object storage in production). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tsp_document")
    @Column(name = "document")
    private Set<String> documents = new LinkedHashSet<>();

    private Instant registeredAt;

    protected Tsp() {
    }

    public Tsp(String tspId, String legalName, String registrationNumber, String bankAccountRef,
               Set<String> modes, Set<String> operators, Set<String> documents, Instant registeredAt) {
        this.tspId = tspId;
        this.legalName = legalName;
        this.registrationNumber = registrationNumber;
        this.bankAccountRef = bankAccountRef;
        this.modes = new LinkedHashSet<>(modes);
        this.operators = new LinkedHashSet<>(operators);
        this.documents = new LinkedHashSet<>(documents);
        this.registeredAt = registeredAt;
    }

    public boolean isOperatedBy(String userId) {
        return operators.contains(userId);
    }

    public String getTspId() { return tspId; }
    public String getLegalName() { return legalName; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getBankAccountRef() { return bankAccountRef; }
    public Set<String> getModes() { return Set.copyOf(modes); }
    public Set<String> getOperators() { return Set.copyOf(operators); }
    public Set<String> getDocuments() { return Set.copyOf(documents); }
    public Instant getRegisteredAt() { return registeredAt; }
}
