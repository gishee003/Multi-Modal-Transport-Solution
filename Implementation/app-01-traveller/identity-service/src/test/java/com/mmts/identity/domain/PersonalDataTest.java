package com.mmts.identity.domain;

import com.mmts.identity.common.security.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Driver D2 at unit level: encryption of a column, masking in logs, and anonymising an account. */
class PersonalDataTest {

    private static final String KEY = "iKPYf3oBawoVMJUsA8hqTGT3vvlDAZwZ2LwLBCqaFIk=";
    private static final String OTHER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void encryptionRoundTripsAndHidesTheValue() {
        EncryptedStringConverter converter = new EncryptedStringConverter(KEY);

        String stored = converter.convertToDatabaseColumn("DL-0420190012345");

        assertThat(stored).doesNotContain("0420190012345");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("DL-0420190012345");
    }

    @Test
    void sameValueIsStoredDifferentlyEachTime() {
        EncryptedStringConverter converter = new EncryptedStringConverter(KEY);

        assertThat(converter.convertToDatabaseColumn("DL-0420190012345"))
                .isNotEqualTo(converter.convertToDatabaseColumn("DL-0420190012345"));   // random IV
    }

    @Test
    void wrongKeyCannotReadTheValue() {
        String stored = new EncryptedStringConverter(KEY).convertToDatabaseColumn("DL-0420190012345");

        assertThatThrownBy(() -> new EncryptedStringConverter(OTHER_KEY).convertToEntityAttribute(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void masksMobileAndLicenceNumbers() {
        assertThat(Masking.mobile("9876543210")).isEqualTo("******3210");
        assertThat(Masking.licence("DL-0420190012345")).isEqualTo("DL-04*********45");
    }

    @Test
    void anonymiseRemovesEveryPersonalField() {
        UserAccount user = new UserAccount("u-1", "Priya Sharma", "9876543210", "priya@example.in", "hash",
                Set.of(Role.TRAVELLER), Instant.parse("2026-01-01T00:00:00Z"));
        user.replaceSavedPlaces(List.of(new SavedPlace("Home", "Rajiv Chowk Metro", 28.63, 77.21, "DMRC-RCK")));
        user.recordLicenceConsent(true, Instant.parse("2026-01-02T00:00:00Z"));

        user.anonymise(Instant.parse("2026-11-15T10:00:00Z"));

        assertThat(user.getId()).isEqualTo("u-1");
        assertThat(user.getFullName()).isNull();
        assertThat(user.getMobile()).isNull();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getSavedPlaces()).isEmpty();
        assertThat(user.getRoles()).isEmpty();
        assertThat(user.isLicenceCheckConsent()).isFalse();
        assertThat(user.getStatus()).isEqualTo(AccountStatus.DELETED);
    }
}
