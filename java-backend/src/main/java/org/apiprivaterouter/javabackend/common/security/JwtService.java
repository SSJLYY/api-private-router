package org.apiprivaterouter.javabackend.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_LENGTH = 32;

    private final JwtProperties jwtProperties;
    private SecretKey cachedSigningKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    public void validateConfiguration() {
        String secret = jwtProperties.secret();
        if (secret == null || secret.isBlank()) {
            // Fail-safe instead of failing startup: generate an ephemeral random secret so the
            // application still boots (matches the documented behavior in .env.example). A fixed
            // JWT_SECRET must still be configured in production, otherwise all sessions are
            // invalidated on every restart.
            secret = generateEphemeralSecret();
            log.warn("JWT_SECRET is not configured. Generated an ephemeral random secret. "
                    + "All user sessions will be invalidated on restart. Set JWT_SECRET (>= {} chars) "
                    + "in production via the API_PRIVATE_ROUTER_JWT_SECRET / JWT_SECRET env var.",
                    MIN_SECRET_LENGTH);
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            log.warn("JWT_SECRET is only {} chars long; padding to the required {} chars for HS256. "
                    + "Please set a strong secret of at least {} characters.",
                    secret.length(), MIN_SECRET_LENGTH, MIN_SECRET_LENGTH);
            secret = padSecret(secret);
        }
        this.cachedSigningKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String generateEphemeralSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String padSecret(String secret) {
        StringBuilder sb = new StringBuilder(MIN_SECRET_LENGTH);
        while (sb.length() < MIN_SECRET_LENGTH) {
            sb.append(secret);
        }
        return sb.substring(0, MIN_SECRET_LENGTH);
    }

    public JwtUserPrincipal parseAccessToken(String token) {
        SecretKey key = signingKey();
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Number userId = claims.get("user_id", Number.class);
        Number tokenVersion = claims.get("token_version", Number.class);
        return new JwtUserPrincipal(
                userId == null ? 0L : userId.longValue(),
                claims.get("email", String.class),
                claims.get("role", String.class),
                tokenVersion == null ? 0L : tokenVersion.longValue()
        );
    }

    public String issueAccessToken(long userId, String email, String role, long tokenVersion) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(getAccessTokenExpiresInSeconds());
        return Jwts.builder()
                .claim("user_id", userId)
                .claim("email", email)
                .claim("role", role)
                .claim("token_version", tokenVersion)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public int getAccessTokenExpiresInSeconds() {
        Integer accessTokenExpireMinutes = jwtProperties.accessTokenExpireMinutes();
        if (accessTokenExpireMinutes != null && accessTokenExpireMinutes > 0) {
            return accessTokenExpireMinutes * 60;
        }
        Integer expireHour = jwtProperties.expireHour();
        if (expireHour != null && expireHour > 0) {
            return expireHour * 3600;
        }
        return 24 * 3600;
    }

    public int getRefreshTokenExpireDays() {
        Integer refreshTokenExpireDays = jwtProperties.refreshTokenExpireDays();
        if (refreshTokenExpireDays != null && refreshTokenExpireDays > 0) {
            return refreshTokenExpireDays;
        }
        return 30;
    }

    private SecretKey signingKey() {
        if (cachedSigningKey == null) {
            // @PostConstruct may not have run (e.g. when constructed directly in unit tests).
            // Fall back to deriving the key lazily so behavior stays correct outside Spring.
            validateConfiguration();
        }
        return cachedSigningKey;
    }
}
