package com.mmts.provider.service;

import com.mmts.provider.common.events.DomainEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens to contract.events from TSP Contract Management (M7, same application APP-02).
 *
 * Business onboarding gates technical onboarding: a TSP's connector is used only while its contract is ACTIVE.
 * - TSP_CONTRACT_ACTIVATED                         -> connector usable
 * - TSP_CONTRACT_SUSPENDED / TSP_CONTRACT_TERMINATED -> no new holds (409 TSP_CONTRACT_INACTIVE), feeds not read
 *
 * In production the topic is Kafka; in the demo the events come from /dev/provider-hub/contracts/... (stand-in for M7).
 */
@Component
public class ContractEventListener {

    static final String TOPIC = "contract.events";

    private final ProviderRegistry registry;
    private final AuditService audit;

    public ContractEventListener(ProviderRegistry registry, AuditService audit) {
        this.registry = registry;
        this.audit = audit;
    }

    @EventListener
    public void onContractEvent(DomainEvent event) {
        if (!TOPIC.equals(event.topic())) {
            return;
        }
        Object tsp = event.payload().get("tspId");
        if (tsp == null) {
            return;
        }
        String tspId = tsp.toString();
        switch (event.type()) {
            case "TSP_CONTRACT_ACTIVATED" -> {
                registry.activateContract(tspId);
                audit.record(null, "TSP_CONNECTOR_ENABLED", "TSP", tspId, "CONTRACT_ACTIVE");
            }
            case "TSP_CONTRACT_SUSPENDED", "TSP_CONTRACT_TERMINATED" -> {
                Object reason = event.payload().getOrDefault("reason", event.type());
                registry.suspendContract(tspId, reason.toString());
                audit.record(null, "TSP_CONNECTOR_DISABLED", "TSP", tspId, reason.toString());
            }
            default -> {
                // other contract events (terms changed, SLA breach warning) do not change the connector
            }
        }
    }
}
