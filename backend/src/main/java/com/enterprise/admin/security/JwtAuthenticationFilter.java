package com.enterprise.admin.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <access token>}.
 *
 * <p>A missing header leaves the request anonymous. An invalid, expired or malformed token also leaves it
 * anonymous (the reason is logged at debug level only), so the authorization rules produce a uniform JSON 401 for
 * protected endpoints while public endpoints keep working. Not a Spring bean on purpose: it must run only inside
 * the security filter chain, never as a separately registered servlet filter.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    /** Our tokens are ~400 characters; anything far larger is rejected before parsing. */
    static final int MAX_TOKEN_LENGTH = 4096;

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (token.length() > MAX_TOKEN_LENGTH) {
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }
            try {
                AuthenticatedUser principal = jwtService.verify(token);
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.authorities());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (InvalidAccessTokenException ex) {
                SecurityContextHolder.clearContext();
                log.debug("Rejected bearer token on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
