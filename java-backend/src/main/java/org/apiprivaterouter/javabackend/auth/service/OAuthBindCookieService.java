package org.apiprivaterouter.javabackend.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class OAuthBindCookieService {

    public static final String COOKIE_NAME = "oauth_bind_access_token";
    private static final String COOKIE_PATH = "/api/v1/auth/oauth";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;

    public ResponseCookie buildAccessTokenCookie(String token, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, URLEncoder.encode(token == null ? "" : token.trim(), StandardCharsets.UTF_8))
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .path(COOKIE_PATH)
                .maxAge(Duration.ofSeconds(COOKIE_MAX_AGE_SECONDS))
                .build();
    }

    public ResponseCookie clearCookie(boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    public boolean isSecure(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.trim().isEmpty()) {
            return "https".equalsIgnoreCase(forwardedProto.trim());
        }
        return request.isSecure();
    }
}
