package com.mmts.booking.service;

import com.mmts.booking.api.ActionResponses.ProblemResponse;
import com.mmts.booking.api.BookingResponse;
import com.mmts.booking.api.BookingResponse.AccessView;
import com.mmts.booking.api.BookingResponse.DriverView;
import com.mmts.booking.api.BookingResponse.LegView;
import com.mmts.booking.api.PlaceDto;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.domain.BookingLeg;
import com.mmts.booking.domain.Place;
import com.mmts.booking.domain.ProblemReport;

/** Turns entities into response records, so entities never leave the service layer. */
final class BookingMapper {

    private BookingMapper() {
    }

    static BookingResponse toResponse(Booking b) {
        return new BookingResponse(b.getId(), b.getUserId(), b.getStatus(), b.getTotalPaise(), b.getPaymentMethod(),
                b.getPaymentId(), b.getRefundId(), b.getCancelReason(), b.getRatingStars(), b.getRatingComment(),
                b.getCreatedAt(), b.getUpdatedAt(), b.getVersion(),
                b.getLegs().stream().map(BookingMapper::toLeg).toList(), b.sagaLogLines());
    }

    static ProblemResponse toProblem(ProblemReport p) {
        return new ProblemResponse(p.getId(), p.getBookingId(), p.getLegId(), p.getCategory(), p.getDescription(),
                p.getStatus(), p.getCreatedAt());
    }

    private static LegView toLeg(BookingLeg l) {
        AccessView access = l.getAccessType() == null ? null
                : new AccessView(l.getAccessType(), l.getAccessValue(), l.getAccessValidFrom(), l.getAccessValidUntil());
        DriverView driver = l.getDriverId() == null ? null
                : new DriverView(l.getDriverId(), l.getDriverName(), l.getDriverVehicleNumber(), l.getDriverEtaMinutes());
        return new LegView(l.getId(), l.getMode(), l.getProviderId(), toPlace(l.getFromPlace()), toPlace(l.getToPlace()),
                l.getStartTime(), l.getVehicleId(), l.getStatus(), l.getHoldId(), l.getPricePaise(), access, driver);
    }

    static PlaceDto toPlace(Place p) {
        return new PlaceDto(p.getName(), p.getLat(), p.getLon(), p.getStopId());
    }
}
