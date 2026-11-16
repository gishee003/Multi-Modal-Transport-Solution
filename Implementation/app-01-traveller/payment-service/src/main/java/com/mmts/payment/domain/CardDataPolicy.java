package com.mmts.payment.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Driver D9: this service never accepts raw card data (card number, CVV, expiry).
 * Card details are typed only into the gateway's own checkout; we only ever see a token (e.g. "tok_...").
 * Keeping card data out means MMTS stays outside most of the PCI DSS card-security rules.
 *
 * This class looks through any JSON request body and lists the places that look like card data:
 * a field with a card-like name, or a text value that is a valid card number (13 to 19 digits passing the
 * Luhn check, the checksum every card number has).
 */
public final class CardDataPolicy {

    /** Field names compared after lower-casing and removing "_" and "-" (so card_number, cardNumber both match). */
    private static final Set<String> CARD_FIELDS = Set.of(
            "cardnumber", "cardno", "pan", "cvv", "cvv2", "cvc", "cvc2", "cardcvv", "securitycode",
            "expiry", "expirydate", "expirymonth", "expiryyear", "expmonth", "expyear", "cardexpiry", "cardpin");

    private CardDataPolicy() {
    }

    /** Returns the JSON paths that hold card data; an empty list means the body is clean. */
    public static List<String> findCardData(JsonNode body) {
        List<String> found = new ArrayList<>();
        walk(body, "$", found);
        return found;
    }

    private static void walk(JsonNode node, String path, List<String> found) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String childPath = path + "." + field.getKey();
                if (CARD_FIELDS.contains(normalise(field.getKey()))) {
                    found.add(childPath);
                } else {
                    walk(field.getValue(), childPath, found);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", found);
            }
        } else if (node.isTextual() && looksLikeCardNumber(node.asText())) {
            found.add(path);
        }
    }

    private static String normalise(String name) {
        return name.toLowerCase().replace("_", "").replace("-", "");
    }

    /** True for 13 to 19 digits (spaces and dashes allowed) that pass the Luhn checksum. */
    public static boolean looksLikeCardNumber(String text) {
        String digits = text.replace(" ", "").replace("-", "");
        if (digits.length() < 13 || digits.length() > 19 || !digits.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
