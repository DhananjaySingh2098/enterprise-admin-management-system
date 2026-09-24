package com.enterprise.admin.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Stateless bearer-token API security.
 *
 * <p>Public: health checks and the cookie-based auth endpoints (login, refresh, logout). Everything else, including
 * every future endpoint, requires a valid access token; role checks use {@code @PreAuthorize} on top.
 *
 * <p><b>Response headers (Phase 6).</b> The API only ever returns JSON, so its CSP forbids everything
 * ({@code default-src 'none'}) and it can never be framed. HSTS is sent only on HTTPS requests (never on local HTTP)
 * and can be disabled for environments without TLS. The obsolete {@code X-Frame-Options} and {@code X-XSS-Protection}
 * headers are not sent: CSP {@code frame-ancestors} supersedes the first and the second is deprecated. The SPA's own
 * CSP is served with the frontend (see docs/SECURITY.md).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_GET_ENDPOINTS = {
            "/api/health",
            "/api/health/db"
    };

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()";

    private static final String[] PUBLIC_POST_ENDPOINTS = {
            "/api/auth/login",
            "/api/auth/refresh",
            // Authenticated by the refresh cookie, not the (possibly expired) access token; idempotent.
            "/api/auth/logout"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   RateLimitFilter rateLimitFilter,
                                                   @Value("${app.security.hsts.enabled:true}") boolean hstsEnabled,
                                                   @Value("${app.security.hsts.max-age:365d}") Duration hstsMaxAge) throws Exception {
        http
                // No session cookies authenticate API calls; the refresh cookie endpoints are protected by
                // SameSite=Strict plus a required X-Requested-With header (see AuthController).
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                // After authentication so per-user limits apply to signed-in callers; before authorization so
                // limited requests never reach controllers.
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
                .headers(headers -> {
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP));
                    headers.frameOptions(frame -> frame.disable());
                    headers.xssProtection(xss -> xss.disable());
                    headers.referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
                    headers.crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN));
                    headers.crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN));
                    headers.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY));
                    if (hstsEnabled) {
                        headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(hstsMaxAge.toSeconds()));
                    } else {
                        headers.httpStrictTransportSecurity(hsts -> hsts.disable());
                    }
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Role rules mirror the controllers' @PreAuthorize (which stays as a second layer). Enforced
                        // here, they run before any request parsing: an unauthorized caller always gets 403, never a
                        // validation response that would reveal the request shape.
                        .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/audit-logs", "/api/audit-logs/**").hasRole("ADMIN")
                        .requestMatchers("/api/settings/organization").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/dashboard/users").hasRole("ADMIN")
                        .requestMatchers("/api/dashboard/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.PUT, "/api/employees/*/status").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/employees").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.PUT, "/api/employees/*").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/departments").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/departments/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(PasswordProperties properties) {
        if (properties.bcryptStrength() < 10) {
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).warn(
                    "BCrypt strength {} is below 10; acceptable for tests only (production default is 12)", properties.bcryptStrength());
        }
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }
}
