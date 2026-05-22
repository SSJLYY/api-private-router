package org.apiprivaterouter.javabackend.userbindings.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.service.CurrentUserService;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.PasswordHasher;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.userbindings.model.BindEmailIdentityRequest;
import org.apiprivaterouter.javabackend.userbindings.model.EmailBindingCodeSession;
import org.apiprivaterouter.javabackend.userbindings.model.EmailBindingRateLimitSession;
import org.apiprivaterouter.javabackend.userbindings.model.SendEmailBindingCodeRequest;
import org.apiprivaterouter.javabackend.userbindings.model.StartIdentityBindingRequest;
import org.apiprivaterouter.javabackend.userbindings.model.StartIdentityBindingResponse;
import org.apiprivaterouter.javabackend.userbindings.repository.UserAccountBindingsRepository;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpEmailService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UserAccountBindingsService {

    private static final List<String> OAUTH_BINDABLE_PROVIDERS = List.of("linuxdo", "oidc", "wechat");
    private static final String DEFAULT_REDIRECT_TO = "/settings/profile";
    private static final List<String> OAUTH_SETTING_KEYS = List.of(
            "linuxdo_connect_enabled",
            "oidc_connect_enabled",
            "wechat_connect_enabled",
            "wechat_connect_open_enabled",
            "wechat_connect_mp_enabled",
            "wechat_connect_mobile_enabled"
    );
    private static final List<String> RESERVED_EMAIL_SUFFIXES = List.of(
            ".invalid",
            "@linuxdo-connect.invalid",
            "@oidc-connect.invalid",
            "@wechat-connect.invalid"
    );
    private static final Duration VERIFY_CODE_TTL = Duration.ofMinutes(15);
    private static final Duration VERIFY_CODE_COOLDOWN = Duration.ofMinutes(1);
    private static final int MAX_VERIFY_CODE_ATTEMPTS = 5;
    private static final int EMAIL_BIND_RATE_LIMIT = 5;
    private static final Duration EMAIL_BIND_RATE_WINDOW = Duration.ofMinutes(10);

    private final UserAccountBindingsRepository repository;
    private final CurrentUserService currentUserService;
    private final PasswordHasher passwordHasher;
    private final UserTotpEmailService emailService;
    private final PublicSettingsService publicSettingsService;

    public UserAccountBindingsService(
            UserAccountBindingsRepository repository,
            CurrentUserService currentUserService,
            PasswordHasher passwordHasher,
            UserTotpEmailService emailService,
            PublicSettingsService publicSettingsService
    ) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.passwordHasher = passwordHasher;
        this.emailService = emailService;
        this.publicSettingsService = publicSettingsService;
    }

    public StartIdentityBindingResponse startIdentityBinding(CurrentUser currentUser, StartIdentityBindingRequest request) {
        requireActiveCurrentUser(currentUser.userId());
        String provider = normalizeProvider(request.provider());
        String redirectTo = normalizeRedirect(request.redirect_to());
        ensureProviderEnabled(provider);
        String authorizeUrl = UriComponentsBuilder.fromPath(bindStartPathForProvider(provider))
                .queryParam("redirect", redirectTo)
                .queryParam("intent", "bind_current_user")
                .build(true)
                .toUriString();
        return new StartIdentityBindingResponse(provider, authorizeUrl, "GET", true);
    }

    @Transactional
    public CurrentUserResponse unbindIdentity(CurrentUser currentUser, String providerRaw) {
        UserAccountBindingsRepository.UserBindingUserRow user = requireActiveCurrentUser(currentUser.userId());
        String provider = normalizeProvider(providerRaw);
        List<UserAccountBindingsRepository.IdentityBindingRow> rows = repository.listIdentityBindings(user.id());
        boolean hasProviderBinding = rows.stream().anyMatch(row -> provider.equals(normalizeOauthProvider(row.provider_type())));
        if (!hasProviderBinding) {
            return currentUserService.getCurrentUser(currentUser);
        }
        if (!canUnbindProvider(provider, user, rows)) {
            throw new IllegalArgumentException("bind another sign-in method before unbinding");
        }
        repository.deleteProviderBindings(user.id(), provider);
        repository.bumpUserTokenVersion(user.id());
        return currentUserService.getCurrentUser(currentUser);
    }

    public Map<String, String> sendEmailBindingCode(CurrentUser currentUser, SendEmailBindingCodeRequest request) {
        UserAccountBindingsRepository.UserBindingUserRow user = requireActiveCurrentUser(currentUser.userId());
        String email = normalizeEmail(request.email());
        ensureNonReservedEmail(email);
        ensureEmailAvailableForUser(email, user.id());

        Instant now = Instant.now();
        ensureCodeCooldown(email, now);
        ensureUserRateLimit(user.id());

        String code = emailService.generateCode();
        emailService.sendVerifyCode(email, resolveSiteName(), code);
        repository.saveCodeSession(email, new EmailBindingCodeSession(
                code,
                0,
                now,
                now.plus(VERIFY_CODE_TTL)
        ));
        incrementRateLimit(user.id(), now);
        return Map.of("message", "Verification code sent successfully");
    }

    @Transactional
    public CurrentUserResponse bindEmailIdentity(CurrentUser currentUser, BindEmailIdentityRequest request) {
        UserAccountBindingsRepository.UserBindingUserRow user = requireActiveCurrentUser(currentUser.userId());
        String email = normalizeEmail(request.email());
        ensureNonReservedEmail(email);
        String verifyCode = trimToNull(request.verify_code());
        if (verifyCode == null) {
            throw new IllegalArgumentException("verify_code is required");
        }
        ensureEmailAvailableForUser(email, user.id());
        verifyCode(email, verifyCode);
        repository.deleteCodeSession(email);
        return bindEmailIdentityInternal(currentUser, user, email, trimToNull(request.password()));
    }

    @Transactional
    public CurrentUserResponse bindEmailIdentityWithoutVerifyCodeForRuntimeReadyPath(CurrentUser currentUser, BindEmailIdentityRequest request) {
        UserAccountBindingsRepository.UserBindingUserRow user = requireActiveCurrentUser(currentUser.userId());
        String email = normalizeEmail(request.email());
        ensureNonReservedEmail(email);
        ensureEmailAvailableForUser(email, user.id());
        return bindEmailIdentityInternal(currentUser, user, email, trimToNull(request.password()));
    }

    private CurrentUserResponse bindEmailIdentityInternal(
            CurrentUser currentUser,
            UserAccountBindingsRepository.UserBindingUserRow user,
            String email,
            String password
    ) {
        if (password == null) {
            throw new IllegalArgumentException("password is required");
        }

        boolean firstRealEmailBind = !hasBindableEmail(user.email());
        if (firstRealEmailBind) {
            if (password.length() < 6) {
                throw new IllegalArgumentException("password must be at least 6 characters");
            }
        } else {
            String currentPasswordHash = trimToNull(user.password_hash());
            if (currentPasswordHash == null || !BCrypt.checkpw(password, currentPasswordHash)) {
                throw new IllegalArgumentException("current password is incorrect");
            }
        }

        String oldEmail = normalizeEmailAllowReserved(user.email());
        String hashedPassword = passwordHasher.hash(password);
        try {
            repository.updateUserEmailAndPassword(user.id(), email, hashedPassword);
            repository.ensureEmailIdentity(user.id(), email, "auth_service_email_bind");
            if (oldEmail != null && !oldEmail.equals(email) && hasBindableEmail(oldEmail)) {
                repository.deleteEmailIdentityBySubject(user.id(), oldEmail);
            }
            repository.bumpUserTokenVersion(user.id());
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("email already exists");
        }
        return currentUserService.getCurrentUser(currentUser);
    }

    private UserAccountBindingsRepository.UserBindingUserRow requireActiveCurrentUser(long userId) {
        return repository.findActiveUserById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    private void ensureProviderEnabled(String provider) {
        Map<String, String> settings = repository.getSettingValues(OAUTH_SETTING_KEYS);
        switch (provider) {
            case "linuxdo" -> {
                if (!isStrictTrue(settings.get("linuxdo_connect_enabled"))) {
                    throw new IllegalArgumentException("linuxdo binding is not enabled");
                }
            }
            case "oidc" -> {
                if (!isStrictTrue(settings.get("oidc_connect_enabled"))) {
                    throw new IllegalArgumentException("oidc binding is not enabled");
                }
            }
            case "wechat" -> {
                if (!isStrictTrue(settings.get("wechat_connect_enabled"))) {
                    throw new IllegalArgumentException("wechat binding is not enabled");
                }
                boolean openEnabled = isStrictTrue(settings.get("wechat_connect_open_enabled"));
                boolean mpEnabled = isStrictTrue(settings.get("wechat_connect_mp_enabled"));
                boolean mobileEnabled = isStrictTrue(settings.get("wechat_connect_mobile_enabled"));
                if (!openEnabled && !mpEnabled && !mobileEnabled) {
                    throw new IllegalArgumentException("wechat binding is not enabled");
                }
            }
            default -> throw new IllegalArgumentException("identity provider is invalid");
        }
    }

    private boolean canUnbindProvider(
            String provider,
            UserAccountBindingsRepository.UserBindingUserRow user,
            List<UserAccountBindingsRepository.IdentityBindingRow> rows
    ) {
        if (!OAUTH_BINDABLE_PROVIDERS.contains(provider)) {
            return false;
        }
        if (canUseEmailAsSignInMethod(user, rows)) {
            return true;
        }
        for (String candidate : OAUTH_BINDABLE_PROVIDERS) {
            if (candidate.equals(provider)) {
                continue;
            }
            boolean exists = rows.stream().anyMatch(row -> candidate.equals(normalizeOauthProvider(row.provider_type())));
            if (exists) {
                return true;
            }
        }
        return false;
    }

    private boolean canUseEmailAsSignInMethod(
            UserAccountBindingsRepository.UserBindingUserRow user,
            List<UserAccountBindingsRepository.IdentityBindingRow> rows
    ) {
        if (!hasBindableEmail(user.email())) {
            return false;
        }
        String signupSource = trimToNull(user.signup_source());
        if (signupSource == null || "email".equalsIgnoreCase(signupSource)) {
            return true;
        }
        return rows.stream()
                .filter(row -> "email".equals(normalizeKnownProvider(row.provider_type())))
                .anyMatch(row -> metadataSupportsEmailSignIn(row.metadata_json()));
    }

    private boolean metadataSupportsEmailSignIn(String metadataJson) {
        if (metadataJson == null) {
            return false;
        }
        String normalized = metadataJson.toLowerCase(Locale.ROOT);
        return normalized.contains("\"source\":\"auth_service_email_bind\"")
                || normalized.contains("\"source\":\"auth_service_login_backfill\"")
                || normalized.contains("\"source\":\"auth_service_dual_write\"");
    }

    private void ensureEmailAvailableForUser(String email, long userId) {
        repository.findActiveUserByEmail(email)
                .filter(existing -> existing.id() != userId)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("email already exists");
                });
        Long identityOwner = repository.findEmailIdentityOwner(email);
        if (identityOwner != null && identityOwner != userId) {
            throw new IllegalArgumentException("email already exists");
        }
    }

    private void verifyCode(String email, String code) {
        EmailBindingCodeSession session = repository.findCodeSession(email);
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
            repository.saveCodeSession(email, new EmailBindingCodeSession(
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
        EmailBindingCodeSession existing = repository.findCodeSession(email);
        if (existing != null && existing.createdAt() != null && existing.createdAt().plus(VERIFY_CODE_COOLDOWN).isAfter(now)) {
            throw new HttpStatusException(429, "please wait before requesting a new code");
        }
    }

    private void ensureUserRateLimit(long userId) {
        EmailBindingRateLimitSession session = repository.findRateLimitSession(userId);
        if (session != null && session.count() >= EMAIL_BIND_RATE_LIMIT) {
            throw new HttpStatusException(429, "too many verification codes requested, please try again later");
        }
    }

    private void incrementRateLimit(long userId, Instant now) {
        EmailBindingRateLimitSession existing = repository.findRateLimitSession(userId);
        if (existing == null) {
            repository.saveRateLimitSession(userId, new EmailBindingRateLimitSession(
                    1,
                    now.plus(EMAIL_BIND_RATE_WINDOW)
            ));
            return;
        }
        repository.saveRateLimitSession(userId, new EmailBindingRateLimitSession(
                existing.count() + 1,
                existing.expiresAt()
        ));
    }

    private String normalizeProvider(String provider) {
        String normalized = trimToNull(provider);
        if (normalized == null) {
            throw new IllegalArgumentException("identity provider is invalid");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if ("email".equals(normalized)) {
            throw new IllegalArgumentException("identity provider is invalid");
        }
        if (!OAUTH_BINDABLE_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("identity provider is invalid");
        }
        return normalized;
    }

    private String normalizeOauthProvider(String provider) {
        String normalized = trimToNull(provider);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return OAUTH_BINDABLE_PROVIDERS.contains(normalized) ? normalized : null;
    }

    private String normalizeKnownProvider(String provider) {
        String normalized = trimToNull(provider);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if ("email".equals(normalized)) {
            return "email";
        }
        return OAUTH_BINDABLE_PROVIDERS.contains(normalized) ? normalized : null;
    }

    private String bindStartPathForProvider(String provider) {
        return switch (provider) {
            case "linuxdo" -> "/api/v1/auth/oauth/linuxdo/bind/start";
            case "oidc" -> "/api/v1/auth/oauth/oidc/bind/start";
            case "wechat" -> "/api/v1/auth/oauth/wechat/bind/start";
            default -> throw new IllegalArgumentException("identity provider is invalid");
        };
    }

    private String normalizeRedirect(String redirectTo) {
        String normalized = trimToNull(redirectTo);
        if (normalized == null) {
            return DEFAULT_REDIRECT_TO;
        }
        if (normalized.length() > 2048 || !normalized.startsWith("/") || normalized.startsWith("//")) {
            throw new IllegalArgumentException("redirect_to is invalid");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        String normalized = trimToNull(email);
        if (normalized == null) {
            throw new IllegalArgumentException("invalid email");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    private String normalizeEmailAllowReserved(String email) {
        String normalized = trimToNull(email);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private void ensureNonReservedEmail(String email) {
        if (!hasBindableEmail(email)) {
            throw new IllegalArgumentException("email is reserved");
        }
    }

    private boolean hasBindableEmail(String email) {
        String normalized = normalizeEmailAllowReserved(email);
        if (normalized == null) {
            return false;
        }
        for (String suffix : RESERVED_EMAIL_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return false;
            }
        }
        return true;
    }

    private boolean isStrictTrue(String value) {
        return "true".equalsIgnoreCase(trimToNull(value));
    }

    private String resolveSiteName() {
        String siteName = trimToNull(publicSettingsService.getPublicSettings().site_name());
        return siteName == null ? "api-private-router" : siteName;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
