package com.mmts.booking.service.saga;

import com.mmts.booking.adapter.LicenceClient.DriveEligibility;
import com.mmts.booking.common.error.ApiException;
import com.mmts.booking.domain.Booking;
import com.mmts.booking.service.DriveEligibilityService;
import org.springframework.stereotype.Component;

/** Step 1: if the journey has a self-drive CAR leg, ask APP-04 whether the traveller may drive (INT-10). */
@Component
public class CheckLicenceStep implements SagaStep {

    private final DriveEligibilityService eligibility;

    public CheckLicenceStep(DriveEligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    @Override
    public String name() {
        return "CHECK_LICENCE";
    }

    @Override
    public String execute(SagaContext context) {
        Booking booking = context.booking();
        if (!booking.hasSelfDriveCar()) {
            return "no self-drive leg, nothing to check";
        }
        DriveEligibility answer;
        try {
            answer = eligibility.checkSelfDrive(booking.getUserId(), "LMV");
        } catch (ApiException e) {
            throw new StepFailed("NOT_ELIGIBLE", e.getCode());
        }
        if (!answer.eligible()) {
            throw new StepFailed("NOT_ELIGIBLE", answer.reason());
        }
        return "licence " + answer.reason();
    }

    @Override
    public String undo(SagaContext context) {
        return null;   // a check changes nothing, so there is nothing to undo
    }
}
