package com.enterprise.admin.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.admin.dto.AuthResponse;
import com.enterprise.admin.dto.CurrentUserResponse;
import com.enterprise.admin.dto.LoginRequest;
import com.enterprise.admin.exception.RequestRejectedException;
import com.enterprise.admin.security.AuthenticatedUser;
import com.enterprise.admin.security.RefreshTokenCookieService;
import com.enterprise.admin.service.AuthService;
import com.enterprise.admin.service.AuthService.AuthSession;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * Required on the cookie-based endpoints. Browsers cannot send custom headers cross-origin without a CORS
     * preflight, so this blocks cross-site form/CSRF requests in addition to the SameSite=Strict cookie.
     */
    public static final String REQUESTED_WITH_HEADER = "X-Requested-With";
    private static final String REQUESTED_WITH_VALUE = "XMLHttpRequest";

    private final AuthService authService;
    private final RefreshTokenCookieService cookieService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestHeader(value = REQUESTED_WITH_HEADER, required = false) String requestedWith,
                                              @Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        requireAjax(requestedWith);
        return withRefreshCookie(authService.login(request, httpRequest.getRemoteAddr()));
    }

    /** 200 with a new token pair; 204 when there is no session cookie (nothing to restore); 401 if invalid. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestHeader(value = REQUESTED_WITH_HEADER, required = false) String requestedWith,
                                                HttpServletRequest httpRequest) {
        requireAjax(requestedWith);
        return cookieService.read(httpRequest)
                .map(token -> withRefreshCookie(authService.refresh(token)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Idempotent: always 204 and always clears the cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = REQUESTED_WITH_HEADER, required = false) String requestedWith,
                                       HttpServletRequest httpRequest) {
        requireAjax(requestedWith);
        cookieService.read(httpRequest).ifPresent(authService::logout);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieService.clear().toString())
                .build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.currentUser(principal.id());
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(AuthSession session) {
        var refresh = session.refreshToken();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.create(refresh.rawValue(), refresh.expiresAt()).toString())
                .body(session.response());
    }

    private static void requireAjax(String requestedWith) {
        if (!REQUESTED_WITH_VALUE.equals(requestedWith)) {
            throw new RequestRejectedException("missing " + REQUESTED_WITH_HEADER + " header");
        }
    }
}
