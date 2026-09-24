package com.enterprise.admin.dto;

/**
 * Returned by login and refresh. The refresh token is never part of the body; it travels only in the
 * HttpOnly cookie.
 *
 * @param expiresIn access-token lifetime in seconds
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, CurrentUserResponse user) {

    public static final String BEARER = "Bearer";

    @Override
    public String toString() {
        return "AuthResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn
                + ", user=" + user + "]";
    }
}
