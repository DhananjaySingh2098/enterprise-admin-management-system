package com.enterprise.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.enterprise.admin.entity.RoleName;
import com.enterprise.testsupport.MutableClock;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

class JwtServiceTest {

    private static final String SECRET = "unit-test-hs256-key-0123456789-abcdefghijklmnop";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final JwtProperties properties = properties(SECRET, "enterprise-admin", "enterprise-admin-web");
    private final JwtService jwtService = new JwtService(properties, clock);

    private static JwtProperties properties(String secret, String issuer, String audience) {
        return new JwtProperties(secret, issuer, audience, Duration.ofMinutes(15), Duration.ofSeconds(30));
    }

    @Test
    void issuedTokenVerifiesAndCarriesOnlyMinimalClaims() throws Exception {
        IssuedAccessToken token = jwtService.issue(42L, EnumSet.of(RoleName.MANAGER, RoleName.USER));

        AuthenticatedUser user = jwtService.verify(token.value());
        assertThat(user.id()).isEqualTo(42L);
        assertThat(user.roles()).containsExactlyInAnyOrder(RoleName.MANAGER, RoleName.USER);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));

        SignedJWT parsed = SignedJWT.parse(token.value());
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(parsed.getHeader().getType()).isEqualTo(new JOSEObjectType("at+jwt"));
        assertThat(parsed.getJWTClaimsSet().getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "iss", "aud", "iat", "exp", "jti", "roles");
        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    void expiredTokenIsRejectedAfterClockSkew() {
        String token = jwtService.issue(1L, Set.of(RoleName.USER)).value();

        clock.advance(Duration.ofMinutes(15).plusSeconds(29));
        assertThat(jwtService.verify(token).id()).isEqualTo(1L);

        clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(InvalidAccessTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void malformedTokensAreRejected() {
        for (String garbage : new String[] {"", "abc", "a.b.c", "eyJhbGciOiJIUzI1NiJ9.e30.", "not a jwt at all"}) {
            assertThatThrownBy(() -> jwtService.verify(garbage)).isInstanceOf(InvalidAccessTokenException.class);
        }
    }

    @Test
    void tamperedPayloadFailsSignatureCheck() {
        String[] parts = jwtService.issue(1L, Set.of(RoleName.USER)).value().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"1\",\"roles\":[\"ADMIN\"],\"iss\":\"enterprise-admin\",\"aud\":\"enterprise-admin-web\","
                        + "\"iat\":" + NOW.getEpochSecond() + ",\"exp\":" + NOW.plusSeconds(600).getEpochSecond() + "}").getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtService.verify(forged)).isInstanceOf(InvalidAccessTokenException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtService other = new JwtService(properties("another-key-with-at-least-32-bytes-of-length!!", "enterprise-admin",
                "enterprise-admin-web"), clock);
        String token = other.issue(1L, Set.of(RoleName.ADMIN)).value();

        assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
    }

    @Test
    void wrongIssuerOrAudienceIsRejected() {
        String wrongIssuer = new JwtService(properties(SECRET, "someone-else", "enterprise-admin-web"), clock)
                .issue(1L, Set.of(RoleName.USER)).value();
        String wrongAudience = new JwtService(properties(SECRET, "enterprise-admin", "another-app"), clock)
                .issue(1L, Set.of(RoleName.USER)).value();

        assertThatThrownBy(() -> jwtService.verify(wrongIssuer)).hasMessageContaining("issuer");
        assertThatThrownBy(() -> jwtService.verify(wrongAudience)).hasMessageContaining("audience");
    }

    @Test
    void unsignedAndWrongTypeTokensAreRejected() throws Exception {
        JWTClaimsSet claims = validClaims().build();
        String unsigned = new PlainJWT(claims).serialize();
        assertThatThrownBy(() -> jwtService.verify(unsigned)).isInstanceOf(InvalidAccessTokenException.class);

        SignedJWT plainJwtType = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        plainJwtType.sign(new MACSigner(SECRET.getBytes()));
        assertThatThrownBy(() -> jwtService.verify(plainJwtType.serialize())).hasMessageContaining("type");
    }

    @Test
    void unknownRoleOrMissingRolesAreRejected() throws Exception {
        assertThatThrownBy(() -> jwtService.verify(sign(validClaims().claim("roles", java.util.List.of("ROOT")).build())))
                .hasMessageContaining("role");
        assertThatThrownBy(() -> jwtService.verify(sign(validClaims().claim("roles", null).build())))
                .hasMessageContaining("roles");
        assertThatThrownBy(() -> jwtService.verify(sign(validClaims().subject("abc").build())))
                .hasMessageContaining("subject");
    }

    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder().subject("7").issuer("enterprise-admin").audience("enterprise-admin-web")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(600)))
                .claim("roles", java.util.List.of("USER"));
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).build(), claims);
        jwt.sign(new MACSigner(SECRET.getBytes()));
        return jwt.serialize();
    }

    @Test
    void fixedClockConstructorWorksWithSystemClockToo() {
        JwtService system = new JwtService(properties, Clock.system(ZoneOffset.UTC));
        assertThat(system.verify(system.issue(5L, Set.of(RoleName.ADMIN)).value()).roles()).containsExactly(RoleName.ADMIN);
    }
}
