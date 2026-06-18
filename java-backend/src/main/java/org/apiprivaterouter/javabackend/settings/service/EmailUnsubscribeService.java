package org.apiprivaterouter.javabackend.settings.service;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.settings.repository.EmailUnsubscribeRepository;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EmailUnsubscribeService {

    private static final long TOKEN_TTL_SECONDS = 365L * 24 * 60 * 60;
    private static final Set<String> OPTIONAL_EVENTS = Set.of(
            "subscription.expiry_reminder",
            "balance.low"
    );

    private final EmailUnsubscribeRepository repository;
    private final JsonHelper jsonHelper;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailUnsubscribeService(EmailUnsubscribeRepository repository, JsonHelper jsonHelper) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
    }

    public UnsubscribeResult unsubscribe(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("invalid or expired unsubscribe token");
        }
        UnsubscribeClaims claims = parseUnsubscribeToken(token);
        if (claims == null) {
            throw new IllegalArgumentException("invalid or expired unsubscribe token");
        }
        if (claims.exp < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("unsubscribe link has expired");
        }
        String event = claims.event;
        if (!OPTIONAL_EVENTS.contains(event.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("this notification cannot be unsubscribed");
        }
        String preferenceKey = "notification_email_preference:v2:" + hashKey(claims.email, event);
        boolean alreadyUnsubscribed = repository.isUnsubscribed(preferenceKey);
        if (!alreadyUnsubscribed) {
            repository.setPreference(preferenceKey, "unsubscribed");
        }
        return new UnsubscribeResult(event, claims.email, true);
    }

    public boolean isUnsubscribed(String email, String event) {
        String preferenceKey = "notification_email_preference:v2:" + hashKey(email, event);
        return repository.isUnsubscribed(preferenceKey);
    }

    public String createUnsubscribeToken(String email, String event) {
        String secret = getOrCreateSecret();
        long exp = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(jsonHelper.writeJson(Map.of(
                        "email", email,
                        "event", event,
                        "exp", exp
                )).getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha256Hex(secret, payload);
        return payload + "." + signature;
    }

    public String buildUnsubscribeUrl(String baseUrl, String email, String event) {
        String token = createUnsubscribeToken(email, event);
        String base = baseUrl == null || baseUrl.isBlank() ? "" : baseUrl.replaceAll("/+$", "");
        return base + "/api/v1/settings/email-unsubscribe?token=" + token;
    }

    private UnsubscribeClaims parseUnsubscribeToken(String token) {
        int dotIndex = token.lastIndexOf('.');
        if (dotIndex <= 0) {
            return null;
        }
        String payload = token.substring(0, dotIndex);
        String signature = token.substring(dotIndex + 1);
        String secret = getOrCreateSecret();
        String expected = hmacSha256Hex(secret, payload);
        if (!expected.equalsIgnoreCase(signature)) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            Map<String, Object> claims = jsonHelper.readObjectMap(new String(decoded, StandardCharsets.UTF_8));
            String email = stringValue(claims.get("email"));
            String event = stringValue(claims.get("event"));
            long exp = claims.get("exp") instanceof Number n ? n.longValue() : 0L;
            if (email.isBlank() || event.isBlank()) {
                return null;
            }
            return new UnsubscribeClaims(email, event, exp);
        } catch (Exception ex) {
            return null;
        }
    }

    private String getOrCreateSecret() {
        String existing = repository.getSecret("notification_email_unsubscribe_secret");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String generated = HexFormat.of().formatHex(bytes);
        repository.setSecret("notification_email_unsubscribe_secret", generated);
        return generated;
    }

    private String hashKey(String email, String event) {
        String combined = (email == null ? "" : email.trim().toLowerCase(Locale.ROOT))
                + ":" + (event == null ? "" : event.trim().toLowerCase(Locale.ROOT));
        return sha256Hex(combined);
    }

    private String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("hmac unavailable", ex);
        }
    }

    private String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("sha256 unavailable", ex);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record UnsubscribeResult(String event, String email, boolean done) {
    }

    private record UnsubscribeClaims(String email, String event, long exp) {
    }
}
