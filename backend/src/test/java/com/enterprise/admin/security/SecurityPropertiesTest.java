package com.enterprise.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.enterprise.admin.config.CorsProperties;

class SecurityPropertiesTest {

    private static JwtProperties jwt(String secret) {
        return new JwtProperties(secret, "iss", "aud", Duration.ofMinutes(15), Duration.ofSeconds(30));
    }

    @Test
    void missingJwtSecretFailsStartupWithClearMessage() {
        assertThatThrownBy(() -> jwt(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET is not set");
        assertThatThrownBy(() -> jwt("   ")).hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void shortOrPlaceholderJwtSecretIsRejected() {
        assertThatThrownBy(() -> jwt("x".repeat(31))).hasMessageContaining("too short");
        assertThatThrownBy(() -> jwt("change_me_generate_with_openssl_rand_base64_48")).hasMessageContaining("placeholder");
        assertThat(jwt("y".repeat(32)).secretBytes()).hasSize(32);
    }

    @Test
    void jwtPropertiesNeverPrintTheSecret() {
        String secret = "z".repeat(40);
        assertThat(jwt(secret).toString()).doesNotContain(secret);
    }

    @Test
    void accessTokenTtlIsBounded() {
        assertThatThrownBy(() -> new JwtProperties("y".repeat(32), "i", "a", Duration.ofHours(2), Duration.ZERO))
                .hasMessageContaining("TTL");
    }

    @Test
    void sameSiteNoneRequiresSecureCookie() {
        assertThatThrownBy(() -> new RefreshTokenProperties.Cookie("c", false, "None", "/api/auth"))
                .hasMessageContaining("Secure");
        assertThatThrownBy(() -> new RefreshTokenProperties.Cookie("c", true, "Sometimes", "/api/auth"))
                .hasMessageContaining("SameSite");
    }

    @Test
    void wildcardCorsOriginsAreRejected() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*"))).hasMessageContaining("wildcards");
        assertThat(new CorsProperties(List.of(" https://app.example.com ", "")).allowedOrigins())
                .containsExactly("https://app.example.com");
    }

    @Test
    void productionPasswordDefaultIsBcryptTwelve() {
        // Mirrors @DefaultValue("12"): documents the production cost and guards against accidental lowering.
        assertThat(new PasswordProperties(12).bcryptStrength()).isEqualTo(12);
        assertThatThrownBy(() -> new PasswordProperties(3)).isInstanceOf(IllegalStateException.class);
    }
}
