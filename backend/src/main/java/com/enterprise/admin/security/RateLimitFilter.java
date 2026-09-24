package com.enterprise.admin.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Applies rate-limit policies to high-risk requests. Read-only requests (GET/HEAD/OPTIONS) are never limited.
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} — per client IP; failed logins are additionally limited per account in
 *       {@code AuthService} (shared across instances).</li>
 *   <li>{@code POST /api/auth/refresh} — per client IP.</li>
 *   <li>{@code PUT /api/profile/password} and {@code PUT /api/profile} — per user.</li>
 *   <li>Mutations of users, employees, departments and organization settings — per user.</li>
 *   <li>Mutations of the caller's own preferences and notifications — per user.</li>
 * </ul>
 *
 * Runs inside the security filter chain (after bearer authentication), never as a stand-alone servlet filter.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RateLimitService rateLimitService;
    private final ApiErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String policy = policyFor(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()));
        if (policy != null) {
            RateLimitService.Decision decision = rateLimitService.consume(policy, subject(policy, request));
            if (!decision.allowed()) {
                long seconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
                errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS,
                        "Too many requests. Please wait a moment and try again.", "RATE_LIMITED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The policy for a request, or {@code null} when it is not limited. Package-private for tests. */
    static String policyFor(String method, String path) {
        if (!MUTATING.contains(method)) {
            return null;
        }
        if ("/api/auth/login".equals(path)) {
            return RateLimitProperties.LOGIN_IP;
        }
        if ("/api/auth/refresh".equals(path)) {
            return RateLimitProperties.REFRESH_IP;
        }
        if ("/api/profile/password".equals(path)) {
            return RateLimitProperties.PASSWORD_CHANGE;
        }
        if ("/api/profile".equals(path)) {
            return RateLimitProperties.PROFILE_UPDATE;
        }
        if (startsWithSegment(path, "/api/users") || startsWithSegment(path, "/api/employees")
                || startsWithSegment(path, "/api/departments") || startsWithSegment(path, "/api/settings")) {
            return RateLimitProperties.ADMIN_MUTATION;
        }
        if (startsWithSegment(path, "/api/preferences") || startsWithSegment(path, "/api/notifications")) {
            return RateLimitProperties.ACCOUNT_MUTATION;
        }
        return null;
    }

    private static boolean startsWithSegment(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** Anonymous endpoints are keyed by client IP; everything else by the authenticated user id. */
    private static String subject(String policy, HttpServletRequest request) {
        if (RateLimitProperties.LOGIN_IP.equals(policy) || RateLimitProperties.REFRESH_IP.equals(policy)) {
            return "ip:" + request.getRemoteAddr();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? "user:" + user.id()
                : "ip:" + request.getRemoteAddr();
    }

    /** Prevents Spring Boot from also registering the filter for every request outside the security chain. */
    @Configuration
    static class Registration {
        @Bean
        FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
            FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }
}
