package com.mmts.booking.service.saga;

import com.mmts.booking.domain.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The saga orchestrator (SOA orchestration, lecture 18). A "saga" is a long piece of work made of several
 * steps on different systems that cannot share one database transaction. Instead, each step has an undo
 * ("compensation"). This class is the controller: it runs the steps in order; if any step fails it undoes
 * every step already started, newest first, and marks the booking CANCELLED with the reason.
 *
 * Order: CHECK_LICENCE -> HOLD_LEGS -> CHARGE_PAYMENT -> CONFIRM_LEGS -> ISSUE_ACCESS.
 */
@Component
public class BookingSaga {

    private static final Logger log = LoggerFactory.getLogger(BookingSaga.class);

    private final List<SagaStep> steps;

    public BookingSaga(CheckLicenceStep checkLicence, HoldLegsStep holdLegs, ChargePaymentStep chargePayment,
                       ConfirmLegsStep confirmLegs, IssueAccessStep issueAccess) {
        this.steps = List.of(checkLicence, holdLegs, chargePayment, confirmLegs, issueAccess);
    }

    /**
     * Runs the whole saga. On success the booking is CONFIRMED.
     *
     * @throws SagaStep.StepFailed after all completed steps were undone and the booking was cancelled
     */
    public Booking run(SagaContext context) {
        Deque<SagaStep> started = new ArrayDeque<>();
        for (SagaStep step : steps) {
            started.push(step);                        // pushed first, so a half-done step also cleans up
            try {
                String note = step.execute(context);
                context.booking().log(step.name() + ": done - " + note);
                context.save();                        // remember progress after every step
            } catch (SagaStep.StepFailed failure) {
                compensate(context, started, step, failure);
                throw failure;
            } catch (RuntimeException unexpected) {
                SagaStep.StepFailed failure = new SagaStep.StepFailed("UNEXPECTED_ERROR", String.valueOf(unexpected.getMessage()));
                compensate(context, started, step, failure);
                throw failure;
            }
        }
        return context.booking();
    }

    /** Undo every started step in reverse order, then cancel the booking. */
    private void compensate(SagaContext context, Deque<SagaStep> started, SagaStep failedStep,
                            SagaStep.StepFailed failure) {
        Booking booking = context.booking();
        booking.log(failedStep.name() + ": FAILED - " + failure.getMessage());
        while (!started.isEmpty()) {
            SagaStep step = started.pop();
            try {
                String note = step.undo(context);
                if (note != null) {
                    booking.log(step.name() + ": undone - " + note);
                }
            } catch (RuntimeException e) {
                // In production this undo would be retried from an outbox; here we log it and carry on.
                log.error("Undo of {} failed for booking {}", step.name(), booking.getId(), e);
                booking.log(step.name() + ": undo FAILED - " + e.getMessage());
            }
        }
        booking.cancel(failure.getMessage(), context.now());
        context.save();
    }
}
