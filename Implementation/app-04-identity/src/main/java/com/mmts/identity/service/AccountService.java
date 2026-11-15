package com.mmts.identity.service;

import com.mmts.identity.api.dto.AuthDtos.AccountType;
import com.mmts.identity.api.dto.AuthDtos.RegisterRequest;
import com.mmts.identity.api.dto.UserDtos.ProfileResponse;
import com.mmts.identity.api.dto.UserDtos.SavedPlaceDto;
import com.mmts.identity.api.dto.UserDtos.UpdateProfileRequest;
import com.mmts.identity.api.dto.UserDtos.UserSummary;
import com.mmts.identity.common.error.ApiException;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.common.security.Role;
import com.mmts.identity.domain.Masking;
import com.mmts.identity.domain.SavedPlace;
import com.mmts.identity.domain.UserAccount;
import com.mmts.identity.repository.DrivingLicenceRepository;
import com.mmts.identity.repository.OtpChallengeRepository;
import com.mmts.identity.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Accounts: register (M1.1), own profile, saved places and consent (M1.3), delete my data (M1.7)
 * and admin role management (M1.8, RBAC).
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserAccountRepository users;
    private final DrivingLicenceRepository licences;
    private final OtpChallengeRepository otps;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public AccountService(UserAccountRepository users, DrivingLicenceRepository licences, OtpChallengeRepository otps,
                          PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.users = users;
        this.licences = licences;
        this.otps = otps;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------- M1.1 Register ----------

    @Transactional
    public ProfileResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        if (users.existsByMobile(request.mobile())) {
            throw ApiException.conflict("MOBILE_ALREADY_REGISTERED", "This mobile number already has an account");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "This email already has an account");
        }
        Role role = request.accountType() == AccountType.DRIVER ? Role.DRIVER : Role.TRAVELLER;
        String id = "u-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = new UserAccount(id, request.fullName().trim(), request.mobile(), email,
                passwordEncoder.encode(request.password()), Set.of(role), clock.instant());
        users.save(user);
        log.info("Registered user {} ({}) with mobile {}", id, role, Masking.mobile(request.mobile()));
        audit.record(id, user.getRoles(), "REGISTER", "USER_ACCOUNT", id, "SUCCESS");
        return toProfile(user);
    }

    // ---------- M1.3 Profile, saved places, consent ----------

    @Transactional(readOnly = true)
    public ProfileResponse profile(String userId) {
        return toProfile(activeUser(userId));
    }

    @Transactional
    public ProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        UserAccount user = activeUser(userId);
        String email = request.email().toLowerCase(Locale.ROOT);
        if (!email.equals(user.getEmail()) && users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "This email already has an account");
        }
        user.updateProfile(request.fullName().trim(), email);
        return toProfile(user);
    }

    @Transactional
    public ProfileResponse replaceSavedPlaces(String userId, List<SavedPlaceDto> places) {
        UserAccount user = activeUser(userId);
        user.replaceSavedPlaces(places.stream()
                .map(p -> new SavedPlace(p.label(), p.name(), p.lat(), p.lon(), p.stopId()))
                .toList());
        return toProfile(user);
    }

    @Transactional
    public ProfileResponse recordConsent(AuthenticatedUser actor, boolean licenceCheck) {
        UserAccount user = activeUser(actor.userId());
        user.recordLicenceConsent(licenceCheck, clock.instant());
        audit.record(actor.userId(), actor.roles(), licenceCheck ? "CONSENT_GIVEN" : "CONSENT_WITHDRAWN",
                "USER_ACCOUNT", actor.userId(), "SUCCESS");
        return toProfile(user);
    }

    // ---------- M1.7 Delete my account and personal data (driver D2) ----------

    @Transactional
    public void deleteMyData(AuthenticatedUser actor) {
        UserAccount user = activeUser(actor.userId());
        if (user.getMobile() != null) {
            otps.deleteById(user.getMobile());
        }
        licences.deleteByUserId(user.getId());   // licence number, classes and dates are removed completely
        user.anonymise(clock.instant());          // name, mobile, email, password, saved places, roles removed
        log.info("Personal data of user {} deleted", user.getId());
        audit.record(actor.userId(), actor.roles(), "DELETE_PERSONAL_DATA", "USER_ACCOUNT", user.getId(), "SUCCESS");
    }

    // ---------- M1.8 Admin: users and roles (RBAC) ----------

    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        return users.findAll(Sort.by("id")).stream()
                .map(u -> new UserSummary(u.getId(), u.getFullName(),
                        u.getMobile() == null ? null : Masking.mobile(u.getMobile()),
                        Set.copyOf(u.getRoles()), u.getStatus()))
                .toList();
    }

    @Transactional
    public UserSummary assignRole(AuthenticatedUser admin, String userId, Role role) {
        if (role == Role.SERVICE) {
            throw ApiException.badRequest("ROLE_NOT_ASSIGNABLE", "SERVICE is only for MMTS applications, not people");
        }
        UserAccount user = activeUser(userId);
        user.addRole(role);
        audit.record(admin.userId(), admin.roles(), "ASSIGN_ROLE_" + role, "USER_ACCOUNT", userId, "SUCCESS");
        return summary(user);
    }

    @Transactional
    public UserSummary removeRole(AuthenticatedUser admin, String userId, Role role) {
        if (admin.userId().equals(userId) && role == Role.ADMIN) {
            throw ApiException.conflict("CANNOT_REMOVE_OWN_ADMIN", "An admin cannot remove their own ADMIN role");
        }
        UserAccount user = activeUser(userId);
        user.removeRole(role);
        audit.record(admin.userId(), admin.roles(), "REMOVE_ROLE_" + role, "USER_ACCOUNT", userId, "SUCCESS");
        return summary(user);
    }

    // ---------- helpers ----------

    private UserAccount activeUser(String userId) {
        return users.findById(userId).filter(UserAccount::isActive)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user with id " + userId));
    }

    private static UserSummary summary(UserAccount u) {
        return new UserSummary(u.getId(), u.getFullName(), Masking.mobile(u.getMobile()), Set.copyOf(u.getRoles()),
                u.getStatus());
    }

    private static ProfileResponse toProfile(UserAccount u) {
        List<SavedPlaceDto> places = u.getSavedPlaces().stream()
                .map(p -> new SavedPlaceDto(p.getLabel(), p.getName(), p.getLat(), p.getLon(), p.getStopId()))
                .toList();
        return new ProfileResponse(u.getId(), u.getFullName(), u.getMobile(), u.getEmail(), Set.copyOf(u.getRoles()),
                places, u.isLicenceCheckConsent(), u.getConsentRecordedAt(), u.getCreatedAt());
    }
}
