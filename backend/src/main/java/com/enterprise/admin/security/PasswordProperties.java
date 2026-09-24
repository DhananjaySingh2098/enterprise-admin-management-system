package com.enterprise.admin.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code app.security.password.*}. Production uses BCrypt cost 12; tests may lower it for speed. */
@ConfigurationProperties(prefix = "app.security.password")
public record PasswordProperties(@DefaultValue("12") int bcryptStrength) {

    public PasswordProperties {
        if (bcryptStrength < 4 || bcryptStrength > 31) {
            throw new IllegalStateException("BCrypt strength must be between 4 and 31.");
        }
    }
}
