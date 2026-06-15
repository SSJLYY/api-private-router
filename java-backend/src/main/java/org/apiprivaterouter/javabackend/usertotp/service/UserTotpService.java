package org.apiprivaterouter.javabackend.usertotp.service;

import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usertotp.model.TotpSetupRequest;
import org.apiprivaterouter.javabackend.usertotp.model.TotpSetupResponse;
import org.apiprivaterouter.javabackend.usertotp.model.TotpStatusResponse;
import org.apiprivaterouter.javabackend.usertotp.model.TotpVerificationMethodResponse;
import org.apiprivaterouter.javabackend.usertotp.repository.UserTotpRepository;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class UserTotpService {

    private static final Duration SETUP_TTL = Duration.ofMinutes(5);
    private static final Duration LOGIN_SESSION_TTL = Duration.ofMinutes(5);
    private static final Duration VERIFY_ATTEMPT_TTL = Duration.ofMinutes(15);
    private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(15);
    private static final Duration EMAIL_CODE_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final int MAX_EMAIL_CODE_ATTEMPTS = 5;
    private static final String ISSUER = "api-private-router";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserTotpRepository repository;
    private final UserTotpCrypto crypto;
    private final UserTotpSessionStore sessionStore;
    private final UserTotpEmailService emailService;
    private final PublicSettingsService publicSettingsService;
    private final Environment environment;

    public UserTotpService(
            UserTotpRepository repository,
            UserTotpCrypto crypto,
            UserTotpSessionStore sessionStore,
            UserTotpEmailService emailService,
            PublicSettingsService publicSettingsService,
            Environment environment
    ) {
        this.repository = repository;
        this.crypto = crypto;
        this.sessionStore = sessionStore;
        this.emailService = emailService;
        this.publicSettingsService = publicSettingsService;
        this.environment = environment;
    }

    public TotpStatusResponse getStatus(CurrentUser currentUser) {
        UserTotpRepository.TotpUserRow user = requireUser(currentUser.userId());
        Long enabledAt = user.totp_enabled_at() == null ? null : user.totp_enabled_at().toEpochSecond();
        return new TotpStatusResponse(
                user.totp_enabled(),
                enabledAt,
                isTotpEnabled()
        );
    }

    public TotpVerificationMethodResponse getVerificationMethod() {
        return new TotpVerificationMethodResponse(isEmailVerifyEnabled() ? "email" : "password");
    }

    public void sendVerifyCode(CurrentUser currentUser) {
        if (!isEmailVerifyEnabled()) {
            throw new HttpStatusException(400, "email verification is not enabled");
        }
        UserTotpRepository.TotpUserRow user = requireUser(currentUser.userId());
        UserTotpSessionStore.EmailCodeSession existing = sessionStore.getEmailCode(user.email());
        if (existing != null && existing.createdAt().plus(EMAIL_CODE_COOLDOWN).isAfter(Instant.now())) {
            throw new HttpStatusException(429, "please wait before requesting a new code");
        }
        String code = emailService.generateCode();
        Instant now = Instant.now();
        String siteName = resolveSiteName();
        emailService.sendVerifyCode(user.email(), siteName, code);
        sessionStore.saveEmailCode(user.email(), new UserTotpSessionStore.EmailCodeSession(
                code,
                0,
                now,
                now.plus(EMAIL_CODE_TTL)
        ));
    }

    public TotpSetupResponse initiateSetup(CurrentUser currentUser, TotpSetupRequest request) {
        if (!isTotpEnabled()) {
            throw new HttpStatusException(400, "totp feature is not enabled");
        }
        UserTotpRepository.TotpUserRow user = requireUser(currentUser.userId());
        if (user.totp_enabled()) {
            throw new HttpStatusException(400, "totp is already enabled for this account");
        }
        String emailCode = request == null ? null : trimToNull(request.email_code());
        String password = request == null ? null : trimToNull(request.password());
        verifyIdentity(user, emailCode, password);

        String secret = UserTotpCodeGenerator.randomBase32Secret(32);
        String setupToken = randomHex(32);
        Instant now = Instant.now();
        sessionStore.saveSetupSession(user.id(), new UserTotpSessionStore.SetupSession(
                secret,
                setupToken,
                now,
                now.plus(SETUP_TTL)
        ));

        return new TotpSetupResponse(
                secret,
                buildOtpAuthUrl(user.email(), secret),
                setupToken,
                (int) SETUP_TTL.getSeconds()
        );
    }

    public void completeSetup(CurrentUser currentUser, String totpCode, String setupToken) {
        if (!isTotpEnabled()) {
            throw new HttpStatusException(400, "totp feature is not enabled");
        }
        UserTotpRepository.TotpUserRow user = requireUser(currentUser.userId());
        UserTotpSessionStore.SetupSession session = sessionStore.getSetupSession(user.id());
        if (session == null) {
            throw new HttpStatusException(400, "totp setup session expired");
        }
        if (!MessageDigest.isEqual(
                session.setupToken().getBytes(StandardCharsets.UTF_8),
                trimToEmpty(setupToken).getBytes(StandardCharsets.UTF_8)
        )) {
            throw new HttpStatusException(400, "totp setup session expired");
        }
        if (!validateTotpCode(session.secret(), totpCode)) {
            throw new HttpStatusException(400, "invalid totp code");
        }

        repository.updateTotpSecret(user.id(), crypto.encrypt(session.secret()));
        repository.enableTotp(user.id());
        sessionStore.deleteSetupSession(user.id());
        sessionStore.clearVerifyAttempts(user.id());
    }

    public void disable(CurrentUser currentUser, String emailCode, String password, String totpCode) {
        UserTotpRepository.TotpUserRow user = requireUser(currentUser.userId());
        if (!user.totp_enabled()) {
            throw new HttpStatusException(400, "totp is not set up for this account");
        }
        verifyIdentity(user, trimToNull(emailCode), trimToNull(password));
        if (trimToNull(totpCode) == null) {
            throw new HttpStatusException(400, "totp_code is required to disable TOTP");
        }
        if (!verifyLoginCode(user.id(), totpCode)) {
            throw new HttpStatusException(400, "invalid totp code");
        }
        repository.disableTotp(user.id());
        sessionStore.clearVerifyAttempts(user.id());
    }

    public boolean verifyLoginCode(long userId, String code) {
        int attempts = sessionStore.getVerifyAttempts(userId);
        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            throw new HttpStatusException(429, "too many verification attempts, please try again later");
        }
        UserTotpRepository.TotpUserRow user = requireUser(userId);
        if (!user.totp_enabled() || trimToNull(user.totp_secret_encrypted()) == null) {
            throw new HttpStatusException(400, "totp is not set up for this account");
        }
        String secret = crypto.decrypt(user.totp_secret_encrypted());
        boolean valid = validateTotpCode(secret, code);
        if (!valid) {
            sessionStore.incrementVerifyAttempts(userId, VERIFY_ATTEMPT_TTL);
            throw new HttpStatusException(400, "invalid totp code");
        }
        sessionStore.clearVerifyAttempts(userId);
        return true;
    }

    public String createLoginSession(long userId, String email) {
        String tempToken = randomHex(32);
        Instant now = Instant.now();
        sessionStore.saveLoginSession(tempToken, new UserTotpSessionStore.LoginSession(
                userId,
                email,
                now.plus(LOGIN_SESSION_TTL)
        ));
        return tempToken;
    }

    public UserTotpSessionStore.LoginSession getLoginSession(String tempToken) {
        return sessionStore.getLoginSession(tempToken);
    }

    public void deleteLoginSession(String tempToken) {
        sessionStore.deleteLoginSession(tempToken);
    }

    public String maskEmail(String email) {
        String normalizedEmail = trimToNull(email);
        if (normalizedEmail == null) {
            return "***";
        }

        int atIndex = normalizedEmail.indexOf('@');
        if (atIndex < 1) {
            return normalizedEmail.substring(0, 1) + "***";
        }

        String localPart = normalizedEmail.substring(0, atIndex);
        String domain = normalizedEmail.substring(atIndex);

        if (localPart.length() <= 2) {
            return localPart.substring(0, 1) + "***" + domain;
        }

        return localPart.substring(0, 1)
                + "***"
                + localPart.substring(localPart.length() - 1)
                + domain;
    }

    private void verifyIdentity(UserTotpRepository.TotpUserRow user, String emailCode, String password) {
        if (isEmailVerifyEnabled()) {
            if (emailCode == null) {
                throw new HttpStatusException(400, "email verification code is required");
            }
            verifyEmailCode(user.email(), emailCode);
            return;
        }
        if (password == null) {
            throw new HttpStatusException(400, "password is required");
        }
        if (trimToNull(user.password_hash()) == null || !BCrypt.checkpw(password, user.password_hash())) {
            throw new IllegalArgumentException("current password is incorrect");
        }
    }

    private void verifyEmailCode(String email, String code) {
        UserTotpSessionStore.EmailCodeSession session = sessionStore.getEmailCode(email);
        if (session == null) {
            throw new HttpStatusException(400, "invalid or expired verification code");
        }
        if (session.attempts() >= MAX_EMAIL_CODE_ATTEMPTS) {
            throw new HttpStatusException(429, "too many failed attempts, please request a new code");
        }
        if (!MessageDigest.isEqual(
                session.code().getBytes(StandardCharsets.UTF_8),
                trimToEmpty(code).getBytes(StandardCharsets.UTF_8)
        )) {
            Instant expiresAt = session.expiresAt();
            sessionStore.saveEmailCode(email, new UserTotpSessionStore.EmailCodeSession(
                    session.code(),
                    session.attempts() + 1,
                    session.createdAt(),
                    expiresAt
            ));
            if (session.attempts() + 1 >= MAX_EMAIL_CODE_ATTEMPTS) {
                throw new HttpStatusException(429, "too many failed attempts, please request a new code");
            }
            throw new HttpStatusException(400, "invalid or expired verification code");
        }
        sessionStore.deleteEmailCode(email);
    }

    private String buildOtpAuthUrl(String email, String secret) {
        String label = urlEncode(ISSUER + ":" + email);
        String issuer = urlEncode(ISSUER);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuer;
    }

    private boolean validateTotpCode(String secret, String code) {
        String normalizedCode = trimToNull(code);
        if (normalizedCode == null || !normalizedCode.matches("\\d{6}")) {
            return false;
        }
        long currentWindow = Instant.now().getEpochSecond() / 30L;
        for (long offset = -1; offset <= 1; offset++) {
            String expected = generateTotpCode(secret, currentWindow + offset);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), normalizedCode.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private String generateTotpCode(String secret, long counter) {
        try {
            byte[] key = decodeBase32(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("failed to generate totp code", ex);
        }
    }

    private byte[] decodeBase32(String input) {
        String normalized = trimToEmpty(input).replace("=", "").toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(normalized.length() * 5 / 8 + 8);
        int bits = 0;
        int value = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            int index = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(ch);
            if (index < 0) {
                throw new IllegalArgumentException("invalid base32 secret");
            }
            value = (value << 5) | index;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                buffer.put((byte) ((value >> bits) & 0xFF));
            }
        }
        buffer.flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private UserTotpRepository.TotpUserRow requireUser(long userId) {
        return repository.findUserById(userId)
                .orElseThrow(() -> new HttpStatusException(404, "user not found"));
    }

    private boolean isTotpEnabled() {
        return publicSettingsService.getPublicSettings().totp_enabled();
    }

    private boolean isEmailVerifyEnabled() {
        return publicSettingsService.getPublicSettings().email_verify_enabled();
    }

    private String resolveSiteName() {
        String value = trimToNull(environment.getProperty("api-private-router.usertotp.site-name"));
        if (value != null) {
            return value;
        }
        return publicSettingsService.getPublicSettings().site_name();
    }

    private String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        SECURE_RANDOM.nextBytes(raw);
        StringBuilder builder = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
