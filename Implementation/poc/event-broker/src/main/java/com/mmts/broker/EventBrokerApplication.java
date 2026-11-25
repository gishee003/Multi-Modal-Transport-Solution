package com.mmts.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.concurrent.CountDownLatch;

/**
 * Proof of concept: the event broker of the deployment (Kafka; VM-13 to VM-15 in production).
 *
 * Starts one real Kafka broker in KRaft mode and creates every MMTS topic. The broker picks a free port and writes
 * its address to the file named by mmts.broker.address-file, which the start script hands to every service. Production runs three
 * brokers with replication factor 3; one broker is enough to show the integration on a laptop, and nothing else
 * needs to be installed (no Docker).
 */
@SpringBootApplication
public class EventBrokerApplication {

    private static final Logger log = LoggerFactory.getLogger(EventBrokerApplication.class);

    /** Every topic of docs/CONTRACTS.md. */
    static final String[] TOPICS = {
            "licence.events", "vehicle.events", "schedule.events", "booking.events", "payment.events",
            "pricing.rules", "contract.events", "tsp.metrics", "audit.events"};

    public static void main(String[] args) {
        SpringApplication.run(EventBrokerApplication.class, args);
    }

    @Bean
    EmbeddedKafkaKraftBroker broker(@Value("${mmts.broker.partitions:3}") int partitions) {
        return new EmbeddedKafkaKraftBroker(1, partitions, TOPICS);   // Spring starts and stops it
    }

    @Bean
    CommandLineRunner announce(EmbeddedKafkaKraftBroker broker,
                               @Value("${mmts.broker.address-file:broker.address}") String addressFile) {
        return args -> {
            java.nio.file.Files.writeString(java.nio.file.Path.of(addressFile), broker.getBrokersAsString());
            log.info("MMTS event broker ready at {} with topics {}", broker.getBrokersAsString(), String.join(", ", TOPICS));
            new CountDownLatch(1).await();   // keep running until the process is stopped
        };
    }
}
