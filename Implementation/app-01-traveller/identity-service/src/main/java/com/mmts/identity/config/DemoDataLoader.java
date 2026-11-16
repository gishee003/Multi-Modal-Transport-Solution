package com.mmts.identity.config;

import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.DrivingLicence;
import com.mmts.identity.domain.SavedPlace;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.domain.VehicleClass;
import com.mmts.identity.repository.DrivingLicenceRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Sample data for the demo profile, so the demo works straight away. Licences are stored directly in their
 * final state (no DigiLocker call and no events at start-up).
 *
 * | Id    | Name           | Mobile     | Password  | Roles             | Licence                                   |
 * | u-101 | Priya Sharma   | 9876543210 | Priya@123 | TRAVELLER         | VERIFIED, LMV + MCWG, until 2031-05-20    |
 * | u-102 | Rahul Verma    | 9123456780 | Rahul@123 | DRIVER            | VERIFIED, LMV, until 2029-08-31           |
 * | u-103 | Ananya Iyer    | 9988776655 | Ananya@123| TRAVELLER         | EXPIRED, LMV, ran out 2024-03-31          |
 * | u-104 | Arjun Nair     | 9445566778 | Arjun@123 | TRAVELLER         | none yet (use him to submit one)          |
 * | u-105 | Vikram Singh   | 9810012345 | Vikram@123| TRAVELLER, DRIVER | still VERIFIED but ran out 2026-10-31: the expiry job will catch it |
 * | u-900 | Meera Krishnan | 9000000001 | Admin@123 | ADMIN             | none                                      |
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private final UserAccountRepository users;
    private final DrivingLicenceRepository licences;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DemoDataLoader(UserAccountRepository users, DrivingLicenceRepository licences,
                          PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.licences = licences;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        Instant now = clock.instant();
        UserAccount priya = user("u-101", "Priya Sharma", "9876543210", "priya.sharma@example.in", "Priya@123",
                Set.of(Role.TRAVELLER));
        priya.replaceSavedPlaces(List.of(
                new SavedPlace("Home", "Rajiv Chowk Metro", 28.6328, 77.2197, "DMRC-RCK"),
                new SavedPlace("Office", "Cyber City, Gurugram", 28.4950, 77.0895, null)));
        priya.recordLicenceConsent(true, now);
        users.save(priya);
        users.save(user("u-102", "Rahul Verma", "9123456780", "rahul.verma@example.in", "Rahul@123",
                Set.of(Role.DRIVER)));
        users.save(user("u-103", "Ananya Iyer", "9988776655", "ananya.iyer@example.in", "Ananya@123",
                Set.of(Role.TRAVELLER)));
        users.save(user("u-104", "Arjun Nair", "9445566778", "arjun.nair@example.in", "Arjun@123",
                Set.of(Role.TRAVELLER)));
        users.save(user("u-105", "Vikram Singh", "9810012345", "vikram.singh@example.in", "Vikram@123",
                Set.of(Role.TRAVELLER, Role.DRIVER)));
        users.save(user("u-900", "Meera Krishnan", "9000000001", "meera.admin@mmts.example.in", "Admin@123",
                Set.of(Role.ADMIN)));

        LocalDate farPast = LocalDate.of(2000, 1, 1);   // "today" for loading: makes every sample start VERIFIED
        licence("u-101", "DL-0420190012345", Set.of(VehicleClass.LMV, VehicleClass.MCWG),
                LocalDate.of(2031, 5, 20), farPast, now);
        licence("u-102", "KA-0520180067890", Set.of(VehicleClass.LMV), LocalDate.of(2029, 8, 31), farPast, now);
        licence("u-103", "MH-1220100098765", Set.of(VehicleClass.LMV), LocalDate.of(2024, 3, 31),
                LocalDate.of(2026, 11, 15), now);   // already past its date: stored as EXPIRED
        licence("u-105", "DL-0920160077777", Set.of(VehicleClass.LMV), LocalDate.of(2026, 10, 31), farPast, now);
    }

    private UserAccount user(String id, String name, String mobile, String email, String password, Set<Role> roles) {
        return new UserAccount(id, name, mobile, email, passwordEncoder.encode(password), roles, clock.instant());
    }

    private void licence(String userId, String number, Set<VehicleClass> classes, LocalDate validUntil,
                         LocalDate checkedAsOf, Instant now) {
        DrivingLicence licence = new DrivingLicence(userId);
        licence.submit(number, null, now);
        licence.markFound(classes, validUntil, checkedAsOf, now);
        licences.save(licence);
    }
}
