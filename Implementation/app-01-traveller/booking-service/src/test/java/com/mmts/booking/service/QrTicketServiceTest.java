package com.mmts.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.booking.BookingProperties;
import com.mmts.booking.service.QrTicketService.QrTicket;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests of the signed QR ticket (M3.3). A fixed clock controls "now". */
class QrTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final QrTicket TICKET = new QrTicket("DMRC-T-301", "b-5001", "b-5001-L1", "METRO", "DMRC",
            "Rajiv Chowk Metro", "Hauz Khas Metro", NOW.minusSeconds(60), NOW.plusSeconds(3 * 3600));

    private static QrTicketService service(String key, Instant now) {
        return new QrTicketService(new BookingProperties(key, 10, 60000, 1.0, null),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void validSignatureIsAccepted() {
        QrTicketService qr = service("gate-key-1", NOW);
        QrTicketService.Verification check = qr.verify(qr.sign(TICKET));
        assertThat(check.valid()).isTrue();
        assertThat(check.reason()).isEqualTo("VALID");
        assertThat(check.ticket()).isEqualTo(TICKET);
    }

    @Test
    void tamperedTicketIsRejected() {
        QrTicketService qr = service("gate-key-1", NOW);
        String[] parts = qr.sign(TICKET).split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String changed = json.replace("Hauz Khas Metro", "Noida City Centre");      // try to ride further
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(StandardCharsets.UTF_8))
                + "." + parts[1];

        QrTicketService.Verification check = qr.verify(forged);
        assertThat(check.valid()).isFalse();
        assertThat(check.reason()).isEqualTo("BAD_SIGNATURE");
    }

    @Test
    void ticketSignedWithAnotherKeyIsRejected() {
        String forged = service("someone-elses-key", NOW).sign(TICKET);
        assertThat(service("gate-key-1", NOW).verify(forged).reason()).isEqualTo("BAD_SIGNATURE");
    }

    @Test
    void expiredTicketIsRejected() {
        String qr = service("gate-key-1", NOW).sign(TICKET);
        QrTicketService.Verification later = service("gate-key-1", NOW.plusSeconds(4 * 3600)).verify(qr);
        assertThat(later.valid()).isFalse();
        assertThat(later.reason()).isEqualTo("EXPIRED");
    }

    @Test
    void garbageIsMalformed() {
        assertThat(service("gate-key-1", NOW).verify("not-a-ticket").reason()).isEqualTo("MALFORMED");
        assertThat(service("gate-key-1", NOW).verify("abc.%%%").reason()).isEqualTo("MALFORMED");
    }
}
