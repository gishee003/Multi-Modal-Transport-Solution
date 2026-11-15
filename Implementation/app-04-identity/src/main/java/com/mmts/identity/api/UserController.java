package com.mmts.identity.api;

import com.mmts.identity.api.dto.UserDtos.ConsentRequest;
import com.mmts.identity.api.dto.UserDtos.ProfileResponse;
import com.mmts.identity.api.dto.UserDtos.RoleRequest;
import com.mmts.identity.api.dto.UserDtos.SavedPlacesRequest;
import com.mmts.identity.api.dto.UserDtos.UpdateProfileRequest;
import com.mmts.identity.api.dto.UserDtos.UserSummary;
import com.mmts.identity.common.security.AuthenticatedUser;
import com.mmts.identity.common.security.Role;
import com.mmts.identity.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Own profile, saved places, consent and "delete my data" (M1.3, M1.7) for the logged-in user,
 * plus user and role management for admins (M1.8). The "me" endpoints always act on the token's user id,
 * so nobody can read or change another person's profile.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "2. Profile and users")
public class UserController {

    private static final String PEOPLE = "hasAnyRole('TRAVELLER','DRIVER','OPERATOR','ADMIN','SUPPORT','FINANCE','REGULATOR')";

    private final AccountService accountService;

    public UserController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/me")
    @PreAuthorize(PEOPLE)
    @Operation(summary = "My profile")
    public ProfileResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.profile(user.userId());
    }

    @PutMapping("/me")
    @PreAuthorize(PEOPLE)
    @Operation(summary = "Change my name and email")
    public ProfileResponse updateMe(@AuthenticationPrincipal AuthenticatedUser user,
                                    @Valid @RequestBody UpdateProfileRequest request) {
        return accountService.updateProfile(user.userId(), request);
    }

    @PutMapping("/me/saved-places")
    @PreAuthorize(PEOPLE)
    @Operation(summary = "Replace my saved places (Home, Office, ...)")
    public ProfileResponse savedPlaces(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody SavedPlacesRequest request) {
        return accountService.replaceSavedPlaces(user.userId(), request.places());
    }

    @PutMapping("/me/consent")
    @PreAuthorize(PEOPLE)
    @Operation(summary = "Give or withdraw consent for licence checks with DigiLocker")
    public ProfileResponse consent(@AuthenticationPrincipal AuthenticatedUser user,
                                   @Valid @RequestBody ConsentRequest request) {
        return accountService.recordConsent(user, request.licenceCheck());
    }

    @DeleteMapping("/me")
    @PreAuthorize(PEOPLE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete my account and all my personal data")
    public void deleteMe(@AuthenticationPrincipal AuthenticatedUser user) {
        accountService.deleteMyData(user);
    }

    // ---------- Admin (RBAC) ----------

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: list all users (mobile numbers masked)")
    public List<UserSummary> list() {
        return accountService.listUsers();
    }

    @PostMapping("/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: give a user a role", description = "Takes effect at the user's next login")
    public UserSummary addRole(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable String userId,
                               @Valid @RequestBody RoleRequest request) {
        return accountService.assignRole(admin, userId, request.role());
    }

    @DeleteMapping("/{userId}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin: take a role away from a user")
    public UserSummary removeRole(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable String userId,
                                  @PathVariable Role role) {
        return accountService.removeRole(admin, userId, role);
    }
}
