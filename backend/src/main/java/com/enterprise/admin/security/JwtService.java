package com.enterprise.admin.security;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.enterprise.admin.entity.RoleName;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Issues and verifies HS256-signed access tokens.
 *
 * <p>Claims are limited to what authorization needs: {@code sub} (user id), {@code roles}, {@code iss}, {@code aud},
 * {@code iat}, {@code exp} and {@code jti}. The header type is {@code at+jwt} (RFC 9068) so that no other kind of
 * JWT signed with the same key can be replayed as an access token.
 */
@Service
public class JwtService {

    static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    static final String ROLES_CLAIM = "roles";

    private final JwtProperties properties;
    private final Clock clock;
    private final JWSSigner signer;
    private final JWSVerifier verifier;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        try {
            this.signer = new MACSigner(properties.secretBytes());
            this.verifier = new MACVerifier(properties.secretBytes());
        } catch (JOSEException ex) {
            throw new IllegalStateException("JWT_SECRET is not usable as an HS256 key", ex);
        }
    }

    public IssuedAccessToken issue(Long userId, Set<RoleName> roles) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .issuer(properties.issuer())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(ROLES_CLAIM, roles.stream().map(Enum::name).sorted().toList())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(ACCESS_TOKEN_TYPE).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign access token", ex);
        }
        return new IssuedAccessToken(jwt.serialize(), expiresAt);
    }

    public AuthenticatedUser verify(String token) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new InvalidAccessTokenException("Malformed token", ex);
        }

        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())) {
            throw new InvalidAccessTokenException("Unexpected algorithm " + header.getAlgorithm());
        }
        if (!ACCESS_TOKEN_TYPE.equals(header.getType())) {
            throw new InvalidAccessTokenException("Unexpected token type");
        }
        try {
            if (!jwt.verify(verifier)) {
                throw new InvalidAccessTokenException("Invalid signature");
            }
        } catch (JOSEException ex) {
            throw new InvalidAccessTokenException("Signature verification failed", ex);
        }

        Instant now = clock.instant();
        Date exp = claims.getExpirationTime();
        Date iat = claims.getIssueTime();
        if (exp == null || !now.isBefore(exp.toInstant().plus(properties.clockSkew()))) {
            throw new InvalidAccessTokenException("Token expired");
        }
        if (iat == null || iat.toInstant().isAfter(now.plus(properties.clockSkew()))) {
            throw new InvalidAccessTokenException("Token issued in the future");
        }
        if (!properties.issuer().equals(claims.getIssuer())) {
            throw new InvalidAccessTokenException("Unexpected issuer");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(properties.audience())) {
            throw new InvalidAccessTokenException("Unexpected audience");
        }

        return new AuthenticatedUser(parseUserId(claims.getSubject()), parseRoles(claims));
    }

    private static Long parseUserId(String subject) {
        try {
            long id = Long.parseLong(subject);
            if (id <= 0) {
                throw new InvalidAccessTokenException("Invalid subject");
            }
            return id;
        } catch (NumberFormatException ex) {
            throw new InvalidAccessTokenException("Invalid subject", ex);
        }
    }

    private static Set<RoleName> parseRoles(JWTClaimsSet claims) {
        List<String> names;
        try {
            names = claims.getStringListClaim(ROLES_CLAIM);
        } catch (ParseException ex) {
            throw new InvalidAccessTokenException("Invalid roles claim", ex);
        }
        if (names == null || names.isEmpty()) {
            throw new InvalidAccessTokenException("Missing roles claim");
        }
        Set<RoleName> roles = EnumSet.noneOf(RoleName.class);
        for (String name : names) {
            try {
                roles.add(RoleName.valueOf(name));
            } catch (IllegalArgumentException ex) {
                throw new InvalidAccessTokenException("Unknown role in token", ex);
            }
        }
        return roles;
    }
}
