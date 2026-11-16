package com.mmts.booking.service.saga;

import com.mmts.booking.adapter.ProviderHubClient.Access;
import com.mmts.booking.adapter.ProviderHubClient.ConfirmResult;
import com.mmts.booking.adapter.ProviderHubClient.Driver;
import com.mmts.booking.domain.AccessType;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.BookingStatus;
import com.mmts.booking.service.QrTicketService;
import com.mmts.booking.service.QrTicketService.QrTicket;
import org.springframework.stereotype.Component;

/**
 * Step 5: give the traveller access to each leg.
 * - METRO / BUS: a signed QR ticket (M3.3)
 * - SCOOTER / BIKE / CAR: the unlock code from the provider (M3.4)
 * - TAXI: the assigned driver (M3.5)
 * The booking refuses to issue anything unless it is PAID (driver D7). At the end the booking is CONFIRMED.
 * Undo: take every ticket and code back.
 */
@Component
public class IssueAccessStep implements SagaStep {

    private final QrTicketService qrTickets;

    public IssueAccessStep(QrTicketService qrTickets) {
        this.qrTickets = qrTickets;
    }

    @Override
    public String name() {
        return "ISSUE_ACCESS";
    }

    @Override
    public String execute(SagaContext context) {
        Booking booking = context.booking();
        int issued = 0;
        for (BookingLeg leg : booking.getLegs()) {
            ConfirmResult confirmation = context.confirmations().get(leg.getId());
            if (confirmation == null) {
                continue;
            }
            Access access = confirmation.access();
            if (leg.getMode().usesQrTicket()) {
                String qr = qrTickets.sign(new QrTicket(access.value(), booking.getId(), leg.getId(), leg.getMode().name(),
                        leg.getProviderId(), leg.getFromPlace().getName(), leg.getToPlace().getName(),
                        access.validFrom(), access.validUntil()));
                booking.issueAccess(leg, AccessType.QR_TICKET, qr, access.validFrom(), access.validUntil());
            } else if (leg.getMode().usesUnlockCode()) {
                booking.issueAccess(leg, AccessType.UNLOCK_CODE, access.value(), access.validFrom(), access.validUntil());
            } else {
                Driver driver = confirmation.driver();
                booking.issueAccess(leg, AccessType.DRIVER_ASSIGNED, driver.driverId(), access.validFrom(), access.validUntil());
                booking.assignDriver(leg, driver.driverId(), driver.name(), driver.vehicleNumber(), driver.etaMinutes());
            }
            issued++;
        }
        booking.moveTo(BookingStatus.CONFIRMED, context.now());
        return "issued access for " + issued + " legs";
    }

    @Override
    public String undo(SagaContext context) {
        context.booking().revokeAllAccess();
        return "tickets and codes withdrawn";
    }
}
