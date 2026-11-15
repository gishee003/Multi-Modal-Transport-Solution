package com.mmts.booking.service.saga;

/**
 * One step of the booking saga (Command pattern): an object that knows how to do its work ({@link #execute})
 * and how to take it back ({@link #undo}). The orchestrator {@link BookingSaga} runs the steps in order and,
 * if one fails, calls undo on every step already started, newest first.
 *
 * Both methods return a short note for the saga log (or null when there is nothing to say).
 */
public interface SagaStep {

    /** Short name shown in the saga log, e.g. HOLD_LEGS. */
    String name();

    /** Does the step. Throws {@link StepFailed} when the step cannot be done. */
    String execute(SagaContext context);

    /**
     * Takes the step back (a "compensation"). Must be safe to call even if execute only got half-way,
     * and safe to call twice. Returns null when there was nothing to undo.
     */
    String undo(SagaContext context);

    /** Thrown by a step that cannot be completed. {@code code} says which kind of failure it was. */
    class StepFailed extends RuntimeException {

        private final String code;
        private final String reason;

        public StepFailed(String code, String reason) {
            super(code + ": " + reason);
            this.code = code;
            this.reason = reason;
        }

        public String code() {
            return code;
        }

        public String reason() {
            return reason;
        }
    }
}
