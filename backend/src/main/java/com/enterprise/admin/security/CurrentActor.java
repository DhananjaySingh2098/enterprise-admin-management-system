package com.enterprise.admin.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated caller's user id, for services that record who made a change. */
public final class CurrentActor {

    private CurrentActor() {
    }

    /** {@code null} for anonymous calls (e.g. login, refresh) and outside a request. */
    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user.id() : null;
    }
}
