package org.apiprivaterouter.javabackend.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Service
public class PendingOAuthCookieService {

    public static final String COOKIE_PATH = "/api/v1/auth/oauth";
    public static final String COOKIE_BROWSER_SESSION = "oauth_pending_browser_session";
    public static final String COOKIE_SESSION = "oauth_pending_session";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;

    public String readPendingSessionToken(HttpServletRequest request) {
        return decodeCookieValue(readCookie(request, COOKIE_SESSION));
    }

    public String readPendingBrowserSessionKey(HttpServletRequest request) {
        return decodeCookieValue(readCookie(request, COOKIE_BROWSER_SESSION));
    }

    public ResponseCookie sessionCookie(String value, boolean secure) {
        return responseCookie(COOKIE_SESSION, encodeCookieValue(value), secure);
    }

    public ResponseCookie browserCookie(String value, boolean secure) {
        return responseCookie(COOKIE_BROWSER_SESSION, encodeCookieValue(value), secure);
    }

    public ResponseCookie clearSessionCookie(boolean secure) {
        return clearCookie(COOKIE_SESSION, secure);
    }

    public ResponseCookie clearBrowserCookie(boolean secure) {
        return clearCookie(COOKIE_BROWSER_SESSION, secure);
    }

    public boolean isSecure(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.trim().isEmpty()) {
            return "https".equalsIgnoreCase(forwardedProto.trim());
        }
        return request.isSecure();
    }

    private ResponseCookie responseCookie(String name, String value, boolean secure) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .maxAge(Duration.ofSeconds(COOKIE_MAX_AGE_SECONDS))
                .path(COOKIE_PATH)
                .build();
    }

    private ResponseCookie clearCookie(String name, boolean secure) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .maxAge(Duration.ZERO)
                .path(COOKIE_PATH)
                .build();
    }

    private String encodeCookieValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCookieValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(raw.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return "";
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return "";
    }
}
