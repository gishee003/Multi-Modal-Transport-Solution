package com.mmts.payment.service;

import com.mmts.payment.api.FinanceDtos.SettlementResponse;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.common.security.AuthenticatedUser;
import com.mmts.payment.config.PaymentSettings;
import com.mmts.payment.domain.Payment;
import com.mmts.payment.domain.SettlementCsv;
import com.mmts.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Closes a day: publishes SETTLEMENT_COMPLETED on payment.events for Analytics service (INT-17) and writes the same
 * figures to exports/settlement-YYYY-MM-DD.csv for the finance team to download. Repeating a day replaces the file.
 */
@Service
public class SettlementService {

    private final PaymentRepository payments;
    private final PaymentEvents events;
    private final Path exportDir;

    public SettlementService(PaymentRepository payments, PaymentEvents events, PaymentSettings settings) {
        this.payments = payments;
        this.events = events;
        this.exportDir = Path.of(settings.settlement().exportDir());
    }

    @Transactional(readOnly = true)
    public SettlementResponse export(LocalDate date, AuthenticatedUser actor) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<SettlementCsv.Row> rows = SettlementCsv.summarise(date,
                payments.findInPeriod(Payment.Status.CAPTURED, from, to));
        String csv = SettlementCsv.format(rows);

        Path file = exportDir.resolve("settlement-" + date + ".csv");
        try {
            Files.createDirectories(exportDir);
            Files.writeString(file, csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED",
                    "Could not write the settlement file " + file);
        }
        events.settled(date, rows);
        events.audit(actor, "SETTLEMENT_EXPORT", "SETTLEMENT_FILE", date.toString(), "SUCCESS");
        return new SettlementResponse(date, file.toString().replace('\\', '/'),
                rows.stream().mapToLong(SettlementCsv.Row::grossPaise).sum(),
                rows.stream().mapToLong(SettlementCsv.Row::commissionPaise).sum(),
                rows.stream().mapToLong(SettlementCsv.Row::netPaise).sum(),
                rows, csv);
    }
}
