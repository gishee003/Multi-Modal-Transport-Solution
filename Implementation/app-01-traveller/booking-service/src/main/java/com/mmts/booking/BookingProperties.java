package com.mmts.booking;

import com.mmts.booking.domain.Mode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * All settings of this application, read from the "mmts.booking" section of application.yml
 * (Externalization pattern: nothing is hard-coded).
 *
 * @param qrSigningKey       secret key used to sign QR tickets (HMAC-SHA256)
 * @param holdTimeoutMinutes how long a booking may wait before payment before it is cancelled
 * @param expiryCheckMs      how often the hold-expiry job runs
 * @param defaultSurgeFactor surge multiplier for on-demand legs until Analytics service sends PRICING_RULES_UPDATED
 * @param stub               settings for the stand-in of TSP Integration Hub (providers it knows, fare per mode)
 */
@ConfigurationProperties(prefix = "mmts.booking")
public record BookingProperties(String qrSigningKey,
                                int holdTimeoutMinutes,
                                long expiryCheckMs,
                                double defaultSurgeFactor,
                                Stub stub) {

    public record Stub(List<String> providers, Map<Mode, Long> faresPaise) {
    }
}
