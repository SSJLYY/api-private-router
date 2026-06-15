package org.apiprivaterouter.javabackend.usertotp.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
// TODO: ConcurrentHashMap不支持集群部署，生产环境应迁移到Redis存储
public class UserTotpSessionStore {

    private final Map<Long, SetupSession> setupSessions = new ConcurrentHashMap<>();
    private final Map<String, LoginSession> loginSessions = new ConcurrentHashMap<>();
    private final Map<Long, Integer> verifyAttempts = new ConcurrentHashMap<>();
    private final Map<Long, Instant> verifyAttemptExpiries = new ConcurrentHashMap<>();
    private final Map<String, EmailCodeSession> emailCodes = new ConcurrentHashMap<>();

    public SetupSession getSetupSession(long userId) {
        SetupSession session = setupSessions.get(userId);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            setupSessions.remove(userId);
            return null;
        }
        return session;
    }

    public void saveSetupSession(long userId, SetupSession session) {
        setupSessions.put(userId, session);
    }

    public void deleteSetupSession(long userId) {
        setupSessions.remove(userId);
    }

    public LoginSession getLoginSession(String tempToken) {
        String normalizedToken = normalizeToken(tempToken);
        LoginSession session = loginSessions.get(normalizedToken);
        if (session == null || session.tokenExpiry().isBefore(Instant.now())) {
            loginSessions.remove(normalizedToken);
            return null;
        }
        return session;
    }

    public void saveLoginSession(String tempToken, LoginSession session) {
        loginSessions.put(normalizeToken(tempToken), session);
    }

    public void deleteLoginSession(String tempToken) {
        loginSessions.remove(normalizeToken(tempToken));
    }

    public int getVerifyAttempts(long userId) {
        Instant expiresAt = verifyAttemptExpiries.get(userId);
        if (expiresAt == null || expiresAt.isBefore(Instant.now())) {
            verifyAttempts.remove(userId);
            verifyAttemptExpiries.remove(userId);
            return 0;
        }
        return verifyAttempts.getOrDefault(userId, 0);
    }

    public int incrementVerifyAttempts(long userId, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        verifyAttemptExpiries.put(userId, expiresAt);
        int next = verifyAttempts.getOrDefault(userId, 0) + 1;
        verifyAttempts.put(userId, next);
        return next;
    }

    public void clearVerifyAttempts(long userId) {
        verifyAttempts.remove(userId);
        verifyAttemptExpiries.remove(userId);
    }

    public EmailCodeSession getEmailCode(String email) {
        EmailCodeSession session = emailCodes.get(normalizeEmail(email));
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            emailCodes.remove(normalizeEmail(email));
            return null;
        }
        return session;
    }

    public void saveEmailCode(String email, EmailCodeSession session) {
        emailCodes.put(normalizeEmail(email), session);
    }

    public void deleteEmailCode(String email) {
        emailCodes.remove(normalizeEmail(email));
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void evictExpired() {
        Instant now = Instant.now();
        setupSessions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        loginSessions.entrySet().removeIf(e -> e.getValue().tokenExpiry().isBefore(now));
        verifyAttemptExpiries.entrySet().removeIf(e -> e.getValue().isBefore(now));
        // Remove verify attempts for users whose expiry was removed
        Iterator<Long> it = verifyAttempts.keySet().iterator();
        while (it.hasNext()) {
            if (!verifyAttemptExpiries.containsKey(it.next())) {
                it.remove();
            }
        }
        emailCodes.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
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
