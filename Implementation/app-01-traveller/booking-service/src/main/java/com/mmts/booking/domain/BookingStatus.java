package com.mmts.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The booking state machine (State pattern). Each state decides which states may come next
 * and which actions are allowed while the booking is in it.
 *
 * <pre>
 * CREATED -> LEGS_HELD -> PAID -> CONFIRMED -> COMPLETED
 *    \___________\__________\________\______-> CANCELLED
 * </pre>
 */
public enum BookingStatus {

    CREATED {
        @Override
        public Set<BookingStatus> allowedNext() {
            return EnumSet.of(LEGS_HELD, CANCELLED);
        }
    },
    LEGS_HELD {
        @Override
        public Set<BookingStatus> allowedNext() {
            return EnumSet.of(PAID, CANCELLED);
        }
    },
    PAID {
        @Override
        public Set<BookingStatus> allowedNext() {
            return EnumSet.of(CONFIRMED, CANCELLED);
        }

        @Override
        public boolean canIssueAccess() {
            return true;
        }
    },
    CONFIRMED {
        @Override
        public Set<BookingStatus> allowedNext() {
            return EnumSet.of(COMPLETED, CANCELLED);
        }

        @Override
        public boolean canIssueAccess() {
            return true;
        }

        @Override
        public boolean canUnlock() {
            return true;
        }
    },
    COMPLETED {
        @Override
        public Set<BookingStatus> allowedNext() {
            return EnumSet.noneOf(BookingStatus.class);
        }

        @Override
        public boolean canRate() {
            return true;
        }
    },
    CANCELLED {
        @Override
        public Set<BookingStatus> allowedNext() {
            return EnumSet.noneOf(BookingStatus.class);
        }
    };

    /** The states this state may move to. */
    public abstract Set<BookingStatus> allowedNext();

    public boolean canMoveTo(BookingStatus next) {
        return allowedNext().contains(next);
    }

    /** Driver D7: tickets and unlock codes exist only once the money is taken (PAID or later). */
    public boolean canIssueAccess() {
        return false;
    }

    /** A vehicle may be unlocked only on a fully confirmed booking. */
    public boolean canUnlock() {
        return false;
    }

    /** Only a finished trip can be rated. */
    public boolean canRate() {
        return false;
    }

    /** Still waiting for payment (these are the bookings the hold-expiry job looks at). */
    public boolean isBeforePayment() {
        return this == CREATED || this == LEGS_HELD;
    }
}
