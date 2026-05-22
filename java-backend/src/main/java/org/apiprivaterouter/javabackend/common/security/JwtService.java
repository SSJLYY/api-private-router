package org.apiprivaterouter.javabackend.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final JwtProperties jwtProperties;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
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
        if (jwtProperties.secret() == null || jwtProperties.secret().isBlank()) {
            throw new IllegalArgumentException("jwt secret is not configured");
        }
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }
}
