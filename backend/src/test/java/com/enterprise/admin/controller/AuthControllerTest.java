package com.enterprise.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.enterprise.admin.TestWebSecurityImports;
import com.enterprise.admin.dto.AuthResponse;
import com.enterprise.admin.dto.CurrentUserResponse;
import com.enterprise.admin.entity.RoleName;
import com.enterprise.admin.entity.User;
import com.enterprise.admin.exception.InvalidCredentialsException;
import com.enterprise.admin.exception.InvalidRefreshTokenException;
import com.enterprise.admin.exception.TooManyLoginAttemptsException;
import com.enterprise.admin.security.JwtService;
import com.enterprise.admin.service.AuthService;
import com.enterprise.admin.service.AuthService.AuthSession;
import com.enterprise.admin.service.RefreshTokenService.IssuedRefreshToken;

import jakarta.servlet.http.Cookie;

/** HTTP contract of the auth endpoints: headers, cookies, status codes and error bodies. */
@WebMvcTest(AuthController.class)
@Import(TestWebSecurityImports.class)
class AuthControllerTest {

    private static final String XHR = "X-Requested-With";
    private static final String LOGIN_JSON = "{\"email\":\"a@example.com\",\"password\":\"whatever-password\"}";
    private static final CurrentUserResponse USER = new CurrentUserResponse(1L, "a@example.com", "Ada", "Lovelace", List.of("ADMIN"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AuthService authService;

    private AuthSession session(String rawRefresh) {
        return new AuthSession(new AuthResponse("access.jwt.value", "Bearer", 900, USER),
                new IssuedRefreshToken(rawRefresh, Instant.now().plus(Duration.ofDays(7)), (User) null));
    }

    @Test
    void loginReturnsTokenInBodyAndRefreshTokenOnlyInHardenedCookie() throws Exception {
        given(authService.login(any(), anyString())).willReturn(session("raw-refresh-value"));

        MvcResult result = mockMvc.perform(post("/api/auth/login").header(XHR, "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value("a@example.com"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).startsWith("ea_refresh_token=raw-refresh-value")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("raw-refresh-value");
    }

    @Test
    void cookieEndpointsRequireAntiCsrfHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Request rejected"));
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("ea_refresh_token", "x")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
        verifyNoInteractions(authService);
    }

    @Test
    void invalidLoginBodyIsRejectedBeforeAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/login").header(XHR, "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(authService);
    }

    @Test
    void failedLoginIsGeneric401() throws Exception {
        given(authService.login(any(), anyString())).willThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login").header(XHR, "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void lockedOutLoginReturns429WithRetryAfter() throws Exception {
        given(authService.login(any(), anyString())).willThrow(new TooManyLoginAttemptsException(Duration.ofSeconds(90)));

        mockMvc.perform(post("/api/auth/login").header(XHR, "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "90"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void refreshWithoutCookieIs204NoSession() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").header(XHR, "XMLHttpRequest"))
                .andExpect(status().isNoContent());
        verifyNoInteractions(authService);
    }

    @Test
    void refreshWithCookieRotatesAndSetsNewCookie() throws Exception {
        given(authService.refresh("old-token")).willReturn(session("new-token"));

        mockMvc.perform(post("/api/auth/refresh").header(XHR, "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", "old-token")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.startsWith("ea_refresh_token=new-token")));
    }

    @Test
    void invalidRefreshClearsCookie() throws Exception {
        given(authService.refresh("bad")).willThrow(new InvalidRefreshTokenException("reused token"));

        mockMvc.perform(post("/api/auth/refresh").header(XHR, "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", "bad")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Session expired. Please sign in again."))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void logoutIsIdempotentAndAlwaysClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header(XHR, "XMLHttpRequest").cookie(new Cookie("ea_refresh_token", "tok")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        verify(authService).logout("tok");

        mockMvc.perform(post("/api/auth/logout").header(XHR, "XMLHttpRequest"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void meRequiresValidBearerToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));

        given(authService.currentUser(1L)).willReturn(USER);
        String token = jwtService.issue(1L, Set.of(RoleName.ADMIN)).value();
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
