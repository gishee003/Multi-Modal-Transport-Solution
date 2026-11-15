package com.mmts.identity.domain;

/**
 * Hides most of a personal value before it is written to a log or shown to staff (driver D2: "never logged").
 * Examples: 9876543210 becomes ******3210, DL-0420190012345 becomes DL-04*********45.
 */
public final class Masking {

    private Masking() {
    }

    public static String mobile(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "****";
        }
        return "*".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 4);
    }

    public static String licence(String number) {
        if (number == null || number.length() < 8) {
            return "****";
        }
        return number.substring(0, 5) + "*".repeat(number.length() - 7) + number.substring(number.length() - 2);
    }
}
