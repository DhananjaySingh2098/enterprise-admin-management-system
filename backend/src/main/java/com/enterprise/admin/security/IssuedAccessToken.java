package com.enterprise.admin.security;

import java.time.Instant;

public record IssuedAccessToken(String value, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedAccessToken[value=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
