package com.mmts.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A transport provider that receives money (DMRC metro, DTC bus, a scooter company, a taxi fleet).
 * {@code providerType} decides which commission rule applies (see CommissionPolicy and application.yml).
 * {@code linkedAccountId} is the provider's account at the payout gateway (Razorpay Route "linked account").
 * It is only a reference; bank details stay with the gateway.
 */
@Entity
@Table(name = "providers")
public class Provider {

    @Id
    private String providerId;

    private String name;

    /** METRO, BUS, SCOOTER, BIKE, CAR or TAXI. */
    private String providerType;

    private String linkedAccountId;

    protected Provider() {
        // for JPA
    }

    public Provider(String providerId, String name, String providerType, String linkedAccountId) {
        this.providerId = providerId;
        this.name = name;
        this.providerType = providerType;
        this.linkedAccountId = linkedAccountId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getName() {
        return name;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getLinkedAccountId() {
        return linkedAccountId;
    }
}
