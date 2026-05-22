package org.apiprivaterouter.javabackend.usernotifyemail.service;

import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;
import org.apiprivaterouter.javabackend.usernotifyemail.model.NotifyEmailCodeSession;
import org.apiprivaterouter.javabackend.usernotifyemail.model.NotifyEmailRateLimitSession;
import org.apiprivaterouter.javabackend.usernotifyemail.model.RemoveNotifyEmailRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.model.SendNotifyEmailCodeRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.model.ToggleNotifyEmailRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.model.VerifyNotifyEmailRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.repository.UserNotifyEmailRepository;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpEmailService;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

@Service
public class UserNotifyEmailService {

    private static final int MAX_NOTIFY_EMAILS = 3;
    private static final int NOTIFY_CODE_USER_RATE_LIMIT = 5;
    private static final Duration NOTIFY_CODE_USER_RATE_WINDOW = Duration.ofMinutes(10);
    private static final Duration VERIFY_CODE_TTL = Duration.ofMinutes(15);
    private static final Duration VERIFY_CODE_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_VERIFY_CODE_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserNotifyEmailRepository repository;
    private final UserTotpEmailService emailService;
    private final PublicSettingsService publicSettingsService;

    public UserNotifyEmailService(
            UserNotifyEmailRepository repository,
            UserTotpEmailService emailService,
            PublicSettingsService publicSettingsService
    ) {
        this.repository = repository;
        this.emailService = emailService;
        this.publicSettingsService = publicSettingsService;
    }

    public void sendCode(CurrentUser currentUser, SendNotifyEmailCodeRequest request) {
        requireUser(currentUser.userId());
        String email = trimToNull(request.email());
        Instant now = Instant.now();

        ensureCodeCooldown(email, now);
        ensureUserRateLimit(currentUser.userId());

        String code = generateCode();
        emailService.sendVerifyCode(email, resolveSiteName(), code);

        repository.saveCodeSession(email, new NotifyEmailCodeSession(
                code,
                0,
                now,
                now.plus(VERIFY_CODE_TTL)
        ));
        incrementRateLimit(currentUser.userId(), now);
    }

    public void verify(CurrentUser currentUser, VerifyNotifyEmailRequest request) {
        String email = trimToNull(request.email());
        verifyCode(email, trimToNull(request.code()));
        repository.deleteCodeSession(email);
        addOrVerifyNotifyEmail(currentUser.userId(), email);
    }

    public void toggle(CurrentUser currentUser, ToggleNotifyEmailRequest request) {
        UserNotifyEmailRepository.NotifyEmailUserRow user = requireUser(currentUser.userId());
        String email = trimToNull(request.email());
        List<NotifyEmailEntry> updated = new ArrayList<>(user.notifyEmails());

        boolean found = false;
        for (int i = 0; i < updated.size(); i++) {
            NotifyEmailEntry entry = updated.get(i);
            if (emailsEqual(entry.email(), email)) {
                updated.set(i, new NotifyEmailEntry(entry.email(), request.disabled(), entry.verified()));
                found = true;
                break;
            }
        }

        if (!found) {
            throw new HttpStatusException(400, "notification email not found");
        }

        repository.updateNotifyEmails(user.id(), updated);
    }

    public void remove(CurrentUser currentUser, RemoveNotifyEmailRequest request) {
        UserNotifyEmailRepository.NotifyEmailUserRow user = requireUser(currentUser.userId());
        String email = trimToNull(request.email());
        List<NotifyEmailEntry> updated = new ArrayList<>();
        boolean found = false;

        for (NotifyEmailEntry entry : user.notifyEmails()) {
            if (emailsEqual(entry.email(), email)) {
                found = true;
                continue;
            }
            updated.add(entry);
        }

        if (!found) {
            throw new HttpStatusException(400, "notification email not found");
        }

        repository.updateNotifyEmails(user.id(), updated);
    }

    private void addOrVerifyNotifyEmail(long userId, String email) {
        UserNotifyEmailRepository.NotifyEmailUserRow user = requireUser(userId);
        List<NotifyEmailEntry> updated = new ArrayList<>(user.notifyEmails());

        for (int i = 0; i < updated.size(); i++) {
            NotifyEmailEntry entry = updated.get(i);
            if (!emailsEqual(entry.email(), email)) {
                continue;
            }
            if (!entry.verified()) {
                updated.set(i, new NotifyEmailEntry(entry.email(), entry.disabled(), true));
                repository.updateNotifyEmails(user.id(), updated);
            }
            return;
        }

        if (updated.size() >= MAX_NOTIFY_EMAILS) {
            throw new HttpStatusException(400, "maximum 3 notification emails allowed");
        }

        updated.add(new NotifyEmailEntry(email, false, true));
        repository.updateNotifyEmails(user.id(), updated);
    }

    private void verifyCode(String email, String code) {
        NotifyEmailCodeSession session = repository.findCodeSession(email);
        if (session == null || code == null) {
            throw new HttpStatusException(400, "invalid or expired verification code");
        }
        if (session.attempts() >= MAX_VERIFY_CODE_ATTEMPTS) {
            throw new HttpStatusException(429, "too many failed attempts, please request a new code");
        }
        if (!MessageDigest.isEqual(
                session.code().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8)
        )) {
            int nextAttempts = session.attempts() + 1;
            repository.saveCodeSession(email, new NotifyEmailCodeSession(
                    session.code(),
                    nextAttempts,
                    session.createdAt(),
                    session.expiresAt()
            ));
            if (nextAttempts >= MAX_VERIFY_CODE_ATTEMPTS) {
                throw new HttpStatusException(429, "too many failed attempts, please request a new code");
            }
            throw new HttpStatusException(400, "invalid or expired verification code");
        }
    }

    private void ensureCodeCooldown(String email, Instant now) {
        NotifyEmailCodeSession existing = repository.findCodeSession(email);
        if (existing != null && existing.createdAt() != null && existing.createdAt().plus(VERIFY_CODE_COOLDOWN).isAfter(now)) {
            throw new HttpStatusException(429, "please wait before requesting a new code");
        }
    }

    private void ensureUserRateLimit(long userId) {
        NotifyEmailRateLimitSession session = repository.findRateLimitSession(userId);
        if (session != null && session.count() >= NOTIFY_CODE_USER_RATE_LIMIT) {
            throw new HttpStatusException(429, "too many verification codes requested, please try again later");
        }
    }

    private void incrementRateLimit(long userId, Instant now) {
        NotifyEmailRateLimitSession existing = repository.findRateLimitSession(userId);
        if (existing == null) {
            repository.saveRateLimitSession(userId, new NotifyEmailRateLimitSession(
                    1,
                    now.plus(NOTIFY_CODE_USER_RATE_WINDOW)
            ));
            return;
        }

        repository.saveRateLimitSession(userId, new NotifyEmailRateLimitSession(
                existing.count() + 1,
                existing.expiresAt()
        ));
    }

    private String generateCode() {
        int value = SECURE_RANDOM.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", value);
    }

    private UserNotifyEmailRepository.NotifyEmailUserRow requireUser(long userId) {
        return repository.findUserById(userId)
                .orElseThrow(() -> new HttpStatusException(404, "user not found"));
    }

    private boolean emailsEqual(String left, String right) {
        return normalizeEmail(left).equals(normalizeEmail(right));
    }

    private String normalizeEmail(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveSiteName() {
        String siteName = publicSettingsService.getPublicSettings().site_name();
        String normalized = trimToNull(siteName);
        if (normalized != null) {
            return normalized;
        }
        return "api-private-router";
    }
}
