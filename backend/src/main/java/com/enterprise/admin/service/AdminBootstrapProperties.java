package com.enterprise.admin.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Initial ADMIN account ({@code ADMIN_EMAIL}, {@code ADMIN_PASSWORD}); used only while no ADMIN exists. */
@ConfigurationProperties(prefix = "app.bootstrap.admin")
public record AdminBootstrapProperties(
        String email,
        String password,
        @DefaultValue("System") String firstName,
        @DefaultValue("Administrator") String lastName) {

    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isEmpty();
    }

    @Override
    public String toString() {
        return "AdminBootstrapProperties[email=" + email + ", password=<redacted>]";
    }
}
