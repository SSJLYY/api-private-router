package org.apiprivaterouter.javabackend.usertotp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class UserTotpSessionStore {

    private static final Logger log = LoggerFactory.getLogger(UserTotpSessionStore.class);

    private static final String SETUP_PREFIX = "totp:setup:";
    private static final String LOGIN_PREFIX = "totp:login:";
    private static final String VERIFY_ATTEMPTS_PREFIX = "totp:verify:attempts:";
    private static final String EMAIL_CODE_PREFIX = "totp:email:code:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public UserTotpSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public SetupSession getSetupSession(long userId) {
        return getJson(SETUP_PREFIX + userId, SetupSession.class, session ->
                session.expiresAt().isBefore(Instant.now()) ? null : session);
    }

    public void saveSetupSession(long userId, SetupSession session) {
        long ttl = Math.max(1, session.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        setJson(SETUP_PREFIX + userId, session, ttl);
    }

    public void deleteSetupSession(long userId) {
        redis.delete(SETUP_PREFIX + userId);
    }

    public LoginSession getLoginSession(String tempToken) {
        String normalizedToken = normalizeToken(tempToken);
        return getJson(LOGIN_PREFIX + normalizedToken, LoginSession.class, session ->
                session.tokenExpiry().isBefore(Instant.now()) ? null : session);
    }

    public void saveLoginSession(String tempToken, LoginSession session) {
        String normalizedToken = normalizeToken(tempToken);
        long ttl = Math.max(1, session.tokenExpiry().getEpochSecond() - Instant.now().getEpochSecond());
        setJson(LOGIN_PREFIX + normalizedToken, session, ttl);
    }

    public void deleteLoginSession(String tempToken) {
        redis.delete(LOGIN_PREFIX + normalizeToken(tempToken));
    }

    public int getVerifyAttempts(long userId) {
        String key = VERIFY_ATTEMPTS_PREFIX + userId;
        String val = redis.opsForValue().get(key);
        if (val == null) {
            return 0;
        }
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl <= 0) {
            redis.delete(key);
            return 0;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            redis.delete(key);
            return 0;
        }
    }

    public int incrementVerifyAttempts(long userId, Duration ttl) {
        String key = VERIFY_ATTEMPTS_PREFIX + userId;
        Long current = redis.opsForValue().increment(key);
        if (current != null && current == 1L) {
            redis.expire(key, ttl);
        }
        return current != null ? current.intValue() : 1;
    }

    public void clearVerifyAttempts(long userId) {
        redis.delete(VERIFY_ATTEMPTS_PREFIX + userId);
    }

    public EmailCodeSession getEmailCode(String email) {
        return getJson(EMAIL_CODE_PREFIX + normalizeEmail(email), EmailCodeSession.class, session ->
                session.expiresAt().isBefore(Instant.now()) ? null : session);
    }

    public void saveEmailCode(String email, EmailCodeSession session) {
        long ttl = Math.max(1, session.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        setJson(EMAIL_CODE_PREFIX + normalizeEmail(email), session, ttl);
    }

    public void deleteEmailCode(String email) {
        redis.delete(EMAIL_CODE_PREFIX + normalizeEmail(email));
    }

    private <T> T getJson(String key, Class<T> type, java.util.function.Function<T, T> expiryCheck) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            T value = objectMapper.readValue(json, type);
            T result = expiryCheck.apply(value);
            if (result == null) {
                redis.delete(key);
            }
            return result;
        } catch (JsonProcessingException e) {
            log.error("failed to deserialize TOTP session key={}: {}", key, e.getMessage());
            redis.delete(key);
            return null;
        }
    }

    private void setJson(String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("failed to serialize TOTP session key={}: {}", key, e.getMessage());
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String normalizeToken(String tempToken) {
        return tempToken == null ? "" : tempToken.trim();
    }

    public record SetupSession(
            String secret,
            String setupToken,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record EmailCodeSession(
            String code,
            int attempts,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record LoginSession(
            long userId,
            String email,
            Instant tokenExpiry
    ) {
    }
}
