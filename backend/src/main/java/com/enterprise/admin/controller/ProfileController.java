package com.enterprise.admin.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.AuthResponse;
import com.enterprise.admin.dto.profile.ChangePasswordRequest;
import com.enterprise.admin.dto.profile.ProfileResponse;
import com.enterprise.admin.dto.profile.UpdateProfileRequest;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.security.RefreshTokenCookieService;
import com.enterprise.admin.service.AuthService.AuthSession;
import com.enterprise.admin.service.ProfileService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** The signed-in user's own account. No endpoint accepts a user id: identity comes from the access token. */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final RefreshTokenCookieService cookieService;

    @GetMapping
    public ProfileResponse get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return profileService.get(principal.id());
    }

    @PutMapping
    public ProfileResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.update(principal.id(), request);
    }

    /**
     * Changes the password, ends every other session and returns a fresh token pair for this one (the new
     * refresh cookie is scoped to /api/auth like any other).
     */
    @PutMapping("/password")
    public ResponseEntity<AuthResponse> changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody ChangePasswordRequest request,
                                                       HttpServletRequest httpRequest) {
        AuthSession session = profileService.changePassword(principal.id(), request, httpRequest.getRemoteAddr());
        var refresh = session.refreshToken();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.create(refresh.rawValue(), refresh.expiresAt()).toString())
                .body(session.response());
    }
}
