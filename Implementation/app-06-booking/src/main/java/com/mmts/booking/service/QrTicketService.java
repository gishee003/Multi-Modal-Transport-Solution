package com.mmts.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.booking.BookingProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * M3.3: makes and checks signed QR tickets for metro and bus legs.
 *
 * A QR text has two parts joined by a dot:  base64url(ticket JSON) "." base64url(HMAC-SHA256 of the first part).
 * HMAC is a "keyed fingerprint": only someone who knows the secret key can produce the right fingerprint,
 * and changing even one letter of the ticket changes the fingerprint. So a gate that knows the key can check a
 * ticket offline (no network call, no database), just by recomputing the fingerprint.
 */
@Service
public class QrTicketService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QrTicketService(BookingProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.key = properties.qrSigningKey().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The data printed inside the QR. */
    public record QrTicket(String ticketId, String bookingId, String legId, String mode, String providerId,
                           String from, String to, Instant validFrom, Instant validUntil) {
    }

    /** reason: VALID, MALFORMED, BAD_SIGNATURE, NOT_YET_VALID or EXPIRED. ticket is null when unreadable. */
    public record Verification(boolean valid, String reason, QrTicket ticket) {
    }

    public String sign(QrTicket ticket) {
        try {
            String body = ENCODER.encodeToString(objectMapper.writeValueAsBytes(ticket));
            return body + "." + ENCODER.encodeToString(hmac(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write QR ticket", e);
        }
    }

    /** What a gate does: check the fingerprint, then the time window. No database is used. */
    public Verification verify(String qr) {
        String[] parts = qr == null ? new String[0] : qr.trim().split("\\.");
        if (parts.length != 2) {
            return new Verification(false, "MALFORMED", null);
        }
        byte[] givenSignature;
        QrTicket ticket;
        try {
            givenSignature = DECODER.decode(parts[1]);
            ticket = objectMapper.readValue(DECODER.decode(parts[0]), QrTicket.class);
        } catch (IllegalArgumentException | java.io.IOException e) {
            return new Verification(false, "MALFORMED", null);
        }
        // constant-time comparison, so an attacker cannot guess the signature byte by byte
        if (!MessageDigest.isEqual(hmac(parts[0]), givenSignature)) {
            return new Verification(false, "BAD_SIGNATURE", null);
        }
        Instant now = clock.instant();
        if (ticket.validFrom() != null && now.isBefore(ticket.validFrom())) {
            return new Verification(false, "NOT_YET_VALID", ticket);
        }
        if (ticket.validUntil() != null && now.isAfter(ticket.validUntil())) {
            return new Verification(false, "EXPIRED", ticket);
        }
        return new Verification(true, "VALID", ticket);
    }

    private byte[] hmac(String text) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}
