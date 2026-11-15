package com.mmts.identity.common.security;

import java.util.Set;

/**
 * Who is calling: read from a valid login token.
 * Controllers can receive it with {@code @AuthenticationPrincipal AuthenticatedUser user}.
 */
public record AuthenticatedUser(String userId, Set<Role> roles) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
