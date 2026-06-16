package org.apiprivaterouter.javabackend.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.admin.subscription.repository.AdminSubscriptionRepository;
import org.apiprivaterouter.javabackend.auth.model.AuthMessageResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthRefreshTokenResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthSendVerifyCodeResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthTokenResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthValidateInvitationCodeResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthValidatePromoCodeResponse;
import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthTokenPair;
import org.apiprivaterouter.javabackend.auth.model.TotpLoginResponse;
import org.apiprivaterouter.javabackend.auth.repository.AuthFlowUserRepository;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.AuthRefreshTokenRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.JwtService;
import org.apiprivaterouter.javabackend.common.security.PasswordHasher;
import org.apiprivaterouter.javabackend.common.security.TokenVersionResolver;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpEmailService;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
public class AuthLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AuthLifecycleService.class);

    private static final String REFRESH_TOKEN_PREFIX = "rt_";
    private static final String TOKEN_TYPE = "Bearer";
    private static final Duration VERIFY_CODE_TTL = Duration.ofMinutes(15);
    private static final Duration VERIFY_CODE_COOLDOWN = Duration.ofMinutes(1);
    private static final Duration PASSWORD_RESET_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration PASSWORD_RESET_EMAIL_COOLDOWN = Duration.ofSeconds(30);
    private static final int MAX_VERIFY_CODE_ATTEMPTS = 5;
    private static final String ROLE_USER = "user";
    private static final String STATUS_ACTIVE = "active";
    private static final String SIGNUP_SOURCE_EMAIL = "email";
    private static final String EMAIL_IDENTITY_SOURCE = "auth_service_dual_write";
    private static final String PROMO_CODE_STATUS_ACTIVE = "active";
    private static final String INVITATION_REDEEM_TYPE = "invitation";
    private static final String REDEEM_STATUS_UNUSED = "unused";
    private static final int AFFILIATE_CODE_MIN_LENGTH = 4;
    private static final int AFFILIATE_CODE_MAX_LENGTH = 32;
    private static final String SETTINGS_KEY_FRONTEND_URL = "frontend_url";
    private static final String SETTINGS_KEY_DEFAULT_CONCURRENCY = "default_concurrency";
    private static final String SETTINGS_KEY_DEFAULT_BALANCE = "default_balance";
    private static final String SETTINGS_KEY_DEFAULT_USER_RPM_LIMIT = "default_user_rpm_limit";
    private static final String SETTINGS_KEY_DEFAULT_SUBSCRIPTIONS = "default_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_BALANCE = "auth_source_default_email_balance";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_CONCURRENCY = "auth_source_default_email_concurrency";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_SUBSCRIPTIONS = "auth_source_default_email_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_GRANT_ON_SIGNUP = "auth_source_default_email_grant_on_signup";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_GRANT_ON_FIRST_BIND = "auth_source_default_email_grant_on_first_bind";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_BALANCE = "auth_source_default_linuxdo_balance";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_CONCURRENCY = "auth_source_default_linuxdo_concurrency";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_SUBSCRIPTIONS = "auth_source_default_linuxdo_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_GRANT_ON_SIGNUP = "auth_source_default_linuxdo_grant_on_signup";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_GRANT_ON_FIRST_BIND = "auth_source_default_linuxdo_grant_on_first_bind";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_BALANCE = "auth_source_default_oidc_balance";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_CONCURRENCY = "auth_source_default_oidc_concurrency";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_SUBSCRIPTIONS = "auth_source_default_oidc_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_GRANT_ON_SIGNUP = "auth_source_default_oidc_grant_on_signup";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_GRANT_ON_FIRST_BIND = "auth_source_default_oidc_grant_on_first_bind";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_BALANCE = "auth_source_default_wechat_balance";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_CONCURRENCY = "auth_source_default_wechat_concurrency";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_SUBSCRIPTIONS = "auth_source_default_wechat_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_GRANT_ON_SIGNUP = "auth_source_default_wechat_grant_on_signup";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_GRANT_ON_FIRST_BIND = "auth_source_default_wechat_grant_on_first_bind";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_BALANCE = "auth_source_default_github_balance";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_CONCURRENCY = "auth_source_default_github_concurrency";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_SUBSCRIPTIONS = "auth_source_default_github_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_GRANT_ON_SIGNUP = "auth_source_default_github_grant_on_signup";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_GRANT_ON_FIRST_BIND = "auth_source_default_github_grant_on_first_bind";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_BALANCE = "auth_source_default_google_balance";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_CONCURRENCY = "auth_source_default_google_concurrency";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_SUBSCRIPTIONS = "auth_source_default_google_subscriptions";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_GRANT_ON_SIGNUP = "auth_source_default_google_grant_on_signup";
    private static final String SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_GRANT_ON_FIRST_BIND = "auth_source_default_google_grant_on_first_bind";
    private static final List<String> RESERVED_EMAIL_SUFFIXES = List.of(
            "@linuxdo-connect.invalid",
            "@oidc-connect.invalid",
            "@wechat-connect.invalid"
    );

    private final AuthFlowUserRepository authFlowUserRepository;
    private final AuthRefreshTokenRepository authRefreshTokenRepository;
    private final AuthPublicEmailRepository authPublicEmailRepository;
    private final JwtService jwtService;
    private final UserTotpService userTotpService;
    private final CurrentUserService currentUserService;
    private final PublicSettingsService publicSettingsService;
    private final AuthTurnstileService authTurnstileService;
    private final PasswordHasher passwordHasher;
    private final UserTotpEmailService userTotpEmailService;
    private final AdminSubscriptionRepository adminSubscriptionRepository;
    private final JsonHelper jsonHelper;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthLifecycleService(
            AuthFlowUserRepository authFlowUserRepository,
            AuthRefreshTokenRepository authRefreshTokenRepository,
            AuthPublicEmailRepository authPublicEmailRepository,
            JwtService jwtService,
            UserTotpService userTotpService,
            CurrentUserService currentUserService,
            PublicSettingsService publicSettingsService,
            AuthTurnstileService authTurnstileService,
            PasswordHasher passwordHasher,
            UserTotpEmailService userTotpEmailService,
            AdminSubscriptionRepository adminSubscriptionRepository,
            JsonHelper jsonHelper,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.authFlowUserRepository = authFlowUserRepository;
        this.authRefreshTokenRepository = authRefreshTokenRepository;
        this.authPublicEmailRepository = authPublicEmailRepository;
        this.jwtService = jwtService;
        this.userTotpService = userTotpService;
        this.currentUserService = currentUserService;
        this.publicSettingsService = publicSettingsService;
        this.authTurnstileService = authTurnstileService;
        this.passwordHasher = passwordHasher;
        this.userTotpEmailService = userTotpEmailService;
        this.adminSubscriptionRepository = adminSubscriptionRepository;
        this.jsonHelper = jsonHelper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public LoginOutcome login(String email, String password, String turnstileToken, String remoteIp) {
        authTurnstileService.verify(turnstileToken, remoteIp);

        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findByEmail(email)
                .orElseThrow(this::invalidCredentials);
        if (!BCrypt.checkpw(password, user.password_hash())) {
            throw invalidCredentials();
        }
        ensureUserEligible(user);

        if (isTotpRequired(user)) {
            String tempToken = userTotpService.createLoginSession(user.id(), user.email());
            return LoginOutcome.totp(new TotpLoginResponse(
                    true,
                    tempToken,
                    userTotpService.maskEmail(user.email())
            ));
        }

        authFlowUserRepository.touchSuccessfulLogin(user.id());
        return LoginOutcome.auth(issueAuthTokenResponse(user, null));
    }

    public boolean canHandleLogin2fa(String tempToken) {
        return userTotpService.getLoginSession(tempToken) != null;
    }

    public AuthTokenResponse completeLogin2fa(String tempToken, String totpCode) {
        var session = userTotpService.getLoginSession(tempToken);
        if (session == null) {
            throw new ApiErrorException(400, "INVALID_2FA_SESSION", "Invalid or expired 2FA session");
        }

        userTotpService.verifyLoginCode(session.userId(), totpCode);
        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findById(session.userId())
                .orElseThrow(() -> new ApiErrorException(404, "USER_NOT_FOUND", "user not found"));
        ensureUserEligible(user);

        userTotpService.deleteLoginSession(tempToken);
        authFlowUserRepository.touchSuccessfulLogin(user.id());
        return issueAuthTokenResponse(user, null);
    }

    public boolean canHandleRefresh(String refreshToken) {
        String normalized = trimToNull(refreshToken);
        if (normalized == null || !normalized.startsWith(REFRESH_TOKEN_PREFIX)) {
            return false;
        }
        return authRefreshTokenRepository.find(hashToken(normalized)).isPresent();
    }

    public AuthRefreshTokenResponse refresh(String refreshToken) {
        String normalized = requireRefreshToken(refreshToken);
        AuthRefreshTokenRepository.RefreshTokenRow stored = authRefreshTokenRepository.consume(hashToken(normalized))
                .orElseThrow(() -> new ApiErrorException(401, "REFRESH_TOKEN_INVALID", "invalid refresh token"));
        if (stored.expiresAt().isBefore(Instant.now())) {
            throw new ApiErrorException(401, "REFRESH_TOKEN_EXPIRED", "refresh token has expired");
        }

        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findById(stored.userId())
                .orElseThrow(() -> new ApiErrorException(401, "REFRESH_TOKEN_INVALID", "invalid refresh token"));
        if (!isActive(user)) {
            authRefreshTokenRepository.deleteByUserId(user.id());
            throw new ApiErrorException(403, "USER_NOT_ACTIVE", "user is not active");
        }
        if (stored.tokenVersion() != resolvedTokenVersion(user)) {
            authRefreshTokenRepository.deleteByUserId(user.id());
            throw new ApiErrorException(401, "TOKEN_REVOKED", "token has been revoked");
        }
        if (isBackendModeEnabled() && !"admin".equalsIgnoreCase(trimToEmpty(user.role()))) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }

        TokenPair pair = issueTokenPair(user, stored.familyId());
        return new AuthRefreshTokenResponse(
                pair.accessToken(),
                pair.refreshToken(),
                pair.expiresIn(),
                TOKEN_TYPE
        );
    }

    public void logout(String refreshToken) {
        String normalized = trimToNull(refreshToken);
        if (normalized == null || !normalized.startsWith(REFRESH_TOKEN_PREFIX)) {
            return;
        }
        authRefreshTokenRepository.delete(hashToken(normalized));
    }

    @Transactional
    public void revokeAllSessions(long userId) {
        authPublicEmailRepository.incrementUserTokenVersion(userId);
        authRefreshTokenRepository.deleteByUserId(userId);
    }

    public AuthSendVerifyCodeResponse sendVerifyCode(String email, String turnstileToken, String remoteIp) {
        authTurnstileService.verify(turnstileToken, remoteIp);
        ensureRegistrationEnabled();
        String normalizedEmail = normalizeEmail(email);
        ensureNonReservedEmail(normalizedEmail);
        ensureRegistrationEmailAllowed(normalizedEmail);
        if (authPublicEmailRepository.existsActiveUserByEmail(normalizedEmail)) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        Instant now = Instant.now();
        AuthPublicEmailRepository.VerifyCodeSession existing = authPublicEmailRepository.findVerifyCodeSession(normalizedEmail);
        if (existing != null && existing.createdAt() != null && existing.createdAt().plus(VERIFY_CODE_COOLDOWN).isAfter(now)) {
            throw new ApiErrorException(429, "VERIFY_CODE_TOO_FREQUENT", "please wait before requesting a new code");
        }

        String code = userTotpEmailService.generateCode();
        try {
            userTotpEmailService.sendVerifyCode(normalizedEmail, resolveSiteName(), code);
        } catch (RuntimeException ex) {
            throw new ApiErrorException(503, "SERVICE_UNAVAILABLE", "service temporarily unavailable");
        }

        authPublicEmailRepository.saveVerifyCodeSession(normalizedEmail, new AuthPublicEmailRepository.VerifyCodeSession(
                code,
                0,
                now,
                now.plus(VERIFY_CODE_TTL)
        ));
        return new AuthSendVerifyCodeResponse("Verification code sent successfully", 60);
    }

    @Transactional
    public AuthTokenResponse register(
            String email,
            String password,
            String verifyCode,
            String turnstileToken,
            String remoteIp,
            String promoCode,
            String invitationCode,
            String affiliateCode
    ) {
        verifyTurnstileForRegister(turnstileToken, remoteIp, verifyCode);
        ensureRegistrationEnabled();
        if (isBackendModeEnabled()) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }

        String normalizedEmail = normalizeEmail(email);
        ensureNonReservedEmail(normalizedEmail);
        ensureRegistrationEmailAllowed(normalizedEmail);

        AuthPublicEmailRepository.RedeemCodeRow invitation = null;
        if (publicSettingsService.getPublicSettings().invitation_code_enabled()) {
            String normalizedInvitationCode = trimToNull(invitationCode);
            if (normalizedInvitationCode == null) {
                throw new ApiErrorException(400, "INVITATION_CODE_REQUIRED", "invitation code is required");
            }
            invitation = authPublicEmailRepository.findRedeemCodeByCodeForUpdate(normalizedInvitationCode)
                    .filter(this::isInvitationCodeUsable)
                    .orElseThrow(() -> new ApiErrorException(400, "INVITATION_CODE_INVALID", "invalid or used invitation code"));
        }

        if (publicSettingsService.getPublicSettings().email_verify_enabled()) {
            if (trimToNull(verifyCode) == null) {
                throw new ApiErrorException(400, "EMAIL_VERIFY_REQUIRED", "email verification is required");
            }
            verifyEmailCode(normalizedEmail, verifyCode);
            authPublicEmailRepository.deleteVerifyCodeSession(normalizedEmail);
        }

        if (authPublicEmailRepository.existsActiveUserByEmail(normalizedEmail)) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        SignupGrantPlan grantPlan = loadSignupGrantPlan();
        long userId;
        try {
            userId = authPublicEmailRepository.createUser(new AuthPublicEmailRepository.CreateUserCommand(
                    normalizedEmail,
                    passwordHasher.hash(password),
                    ROLE_USER,
                    grantPlan.balance(),
                    grantPlan.concurrency(),
                    grantPlan.rpmLimit(),
                    STATUS_ACTIVE,
                    "",
                    "",
                    SIGNUP_SOURCE_EMAIL
            ));
        } catch (DuplicateKeyException ex) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        try {
            authPublicEmailRepository.ensureEmailIdentity(userId, normalizedEmail, EMAIL_IDENTITY_SOURCE);
        } catch (DuplicateKeyException ignored) {
            // Best effort only. The user row is already the source of truth for email auth.
        }
        authPublicEmailRepository.ensureUserAffiliate(userId);
        assignDefaultSubscriptions(userId, grantPlan.subscriptions());

        if (invitation != null) {
            authPublicEmailRepository.markRedeemCodeUsed(invitation.id(), userId);
        }
        applyPromoCodeBestEffort(userId, promoCode);
        bindAffiliateInviterBestEffort(userId, affiliateCode);

        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findById(userId)
                .orElseThrow(() -> new ApiErrorException(503, "SERVICE_UNAVAILABLE", "service temporarily unavailable"));
        ensureUserEligible(user);
        authFlowUserRepository.touchSuccessfulLogin(user.id());
        return issueAuthTokenResponse(user, null);
    }

    public AuthMessageResponse forgotPassword(String email, String turnstileToken, String remoteIp) {
        authTurnstileService.verify(turnstileToken, remoteIp);
        if (!publicSettingsService.getPublicSettings().password_reset_enabled()) {
            throw new ApiErrorException(403, "PASSWORD_RESET_DISABLED", "password reset is not enabled");
        }

        String frontendUrl = trimToNull(authPublicEmailRepository.getSettingValue(SETTINGS_KEY_FRONTEND_URL));
        if (frontendUrl == null) {
            throw new RuntimeException("Password reset is not configured");
        }

        String normalizedEmail = normalizeEmail(email);
        AuthPublicEmailRepository.PublicAuthUserRow user = authPublicEmailRepository.findUserByEmail(normalizedEmail)
                .orElse(null);
        if (user == null || !STATUS_ACTIVE.equalsIgnoreCase(trimToEmpty(user.status()))) {
            return forgotPasswordSuccess();
        }

        AuthPublicEmailRepository.PasswordResetEmailCooldownSession cooldownSession =
                authPublicEmailRepository.findPasswordResetEmailCooldownSession(normalizedEmail);
        if (cooldownSession != null && cooldownSession.expiresAt() != null && cooldownSession.expiresAt().isAfter(Instant.now())) {
            return forgotPasswordSuccess();
        }

        Instant now = Instant.now();
        String token = randomHex(32);
        authPublicEmailRepository.savePasswordResetTokenSession(normalizedEmail, new AuthPublicEmailRepository.PasswordResetTokenSession(
                token,
                now,
                now.plus(PASSWORD_RESET_TOKEN_TTL)
        ));

        String resetUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        resetUrl = resetUrl + "/reset-password?email=" + urlEncode(normalizedEmail) + "&token=" + urlEncode(token);
        try {
            userTotpEmailService.sendPasswordResetEmail(normalizedEmail, resolveSiteName(), resetUrl);
            authPublicEmailRepository.savePasswordResetEmailCooldownSession(normalizedEmail, new AuthPublicEmailRepository.PasswordResetEmailCooldownSession(
                    now,
                    now.plus(PASSWORD_RESET_EMAIL_COOLDOWN)
            ));
        } catch (RuntimeException ignored) {
            log.warn("Password reset email failed to send for user_id={}: {}", user.id(), ignored.getMessage());
        }
        return forgotPasswordSuccess();
    }

    @Transactional
    public AuthMessageResponse resetPassword(String email, String token, String newPassword) {
        if (!publicSettingsService.getPublicSettings().password_reset_enabled()) {
            throw new ApiErrorException(403, "PASSWORD_RESET_DISABLED", "password reset is not enabled");
        }

        String normalizedEmail = normalizeEmail(email);
        consumePasswordResetToken(normalizedEmail, token);

        AuthPublicEmailRepository.PublicAuthUserRow user = authPublicEmailRepository.findUserByEmail(normalizedEmail)
                .orElseThrow(() -> new ApiErrorException(400, "INVALID_RESET_TOKEN", "invalid or expired password reset token"));
        if (!STATUS_ACTIVE.equalsIgnoreCase(trimToEmpty(user.status()))) {
            throw new ApiErrorException(403, "USER_NOT_ACTIVE", "user is not active");
        }

        authPublicEmailRepository.incrementUserTokenVersionAndPassword(user.id(), passwordHasher.hash(newPassword));
        deleteRefreshTokensForUser(user.id());
        return new AuthMessageResponse("Your password has been reset successfully. You can now log in with your new password.");
    }

    public AuthValidatePromoCodeResponse validatePromoCode(String code) {
        if (!publicSettingsService.getPublicSettings().promo_code_enabled()) {
            return new AuthValidatePromoCodeResponse(false, null, "PROMO_CODE_DISABLED", null);
        }
        String normalizedCode = trimToNull(code);
        if (normalizedCode == null) {
            return new AuthValidatePromoCodeResponse(false, null, "PROMO_CODE_INVALID", null);
        }
        AuthPublicEmailRepository.PromoCodeRow promo = authPublicEmailRepository.findPromoCodeByCode(normalizedCode)
                .orElse(null);
        if (promo == null) {
            return new AuthValidatePromoCodeResponse(false, null, "PROMO_CODE_NOT_FOUND", null);
        }
        if (!PROMO_CODE_STATUS_ACTIVE.equalsIgnoreCase(trimToEmpty(promo.status()))) {
            return new AuthValidatePromoCodeResponse(false, null, "PROMO_CODE_DISABLED", null);
        }
        if (promo.expiresAt() != null && promo.expiresAt().isBefore(OffsetDateTime.now())) {
            return new AuthValidatePromoCodeResponse(false, null, "PROMO_CODE_EXPIRED", null);
        }
        if (promo.maxUses() > 0 && promo.usedCount() >= promo.maxUses()) {
            return new AuthValidatePromoCodeResponse(false, null, "PROMO_CODE_MAX_USED", null);
        }
        return new AuthValidatePromoCodeResponse(true, promo.bonusAmount(), null, null);
    }

    public AuthValidateInvitationCodeResponse validateInvitationCode(String code) {
        if (!publicSettingsService.getPublicSettings().invitation_code_enabled()) {
            return new AuthValidateInvitationCodeResponse(false, "INVITATION_CODE_DISABLED");
        }
        String normalizedCode = trimToNull(code);
        if (normalizedCode == null) {
            return new AuthValidateInvitationCodeResponse(false, "INVITATION_CODE_NOT_FOUND");
        }
        AuthPublicEmailRepository.RedeemCodeRow redeemCode = authPublicEmailRepository.findRedeemCodeByCodeForUpdate(normalizedCode)
                .orElse(null);
        if (redeemCode == null) {
            return new AuthValidateInvitationCodeResponse(false, "INVITATION_CODE_NOT_FOUND");
        }
        if (!INVITATION_REDEEM_TYPE.equalsIgnoreCase(trimToEmpty(redeemCode.type()))) {
            return new AuthValidateInvitationCodeResponse(false, "INVITATION_CODE_INVALID");
        }
        if (!REDEEM_STATUS_UNUSED.equalsIgnoreCase(trimToEmpty(redeemCode.status()))) {
            return new AuthValidateInvitationCodeResponse(false, "INVITATION_CODE_USED");
        }
        return new AuthValidateInvitationCodeResponse(true, null);
    }

    public void deleteRefreshTokensForUser(long userId) {
        authRefreshTokenRepository.deleteByUserId(userId);
    }

    public PendingOAuthTokenPair issueTokenPairForUser(long userId) {
        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findById(userId)
                .orElseThrow(() -> new ApiErrorException(404, "USER_NOT_FOUND", "user not found"));
        ensureUserEligible(user);
        authFlowUserRepository.touchSuccessfulLogin(user.id());
        AuthTokenResponse response = issueAuthTokenResponse(user, null);
        return new PendingOAuthTokenPair(
                response.access_token(),
                response.refresh_token(),
                response.expires_in(),
                response.token_type(),
                response.user()
        );
    }

    @Transactional
    public AuthTokenResponse registerVerifiedOAuthEmailAccount(
            String email,
            String password,
            String invitationCode,
            String signupSource,
            String affiliateCode
    ) {
        if (isBackendModeEnabled()) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }
        ensureRegistrationEnabled();

        String normalizedEmail = normalizeEmail(email);
        ensureNonReservedEmail(normalizedEmail);
        ensureRegistrationEmailAllowed(normalizedEmail);
        if (trimToNull(password) == null) {
            throw new ApiErrorException(400, "PASSWORD_REQUIRED", "password is required");
        }

        AuthPublicEmailRepository.RedeemCodeRow invitation = null;
        if (publicSettingsService.getPublicSettings().invitation_code_enabled()) {
            String normalizedInvitationCode = trimToNull(invitationCode);
            if (normalizedInvitationCode == null) {
                throw new ApiErrorException(400, "INVITATION_CODE_REQUIRED", "invitation code is required");
            }
            invitation = authPublicEmailRepository.findRedeemCodeByCodeForUpdate(normalizedInvitationCode)
                    .filter(this::isInvitationCodeUsable)
                    .orElseThrow(() -> new ApiErrorException(400, "INVITATION_CODE_INVALID", "invalid or used invitation code"));
        }

        if (authPublicEmailRepository.existsActiveUserByEmail(normalizedEmail)) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        String resolvedSignupSource = normalizeSignupSource(signupSource);
        SignupGrantPlan grantPlan = loadSignupGrantPlan(resolvedSignupSource);
        long userId;
        try {
            userId = authPublicEmailRepository.createUser(new AuthPublicEmailRepository.CreateUserCommand(
                    normalizedEmail,
                    passwordHasher.hash(password),
                    ROLE_USER,
                    grantPlan.balance(),
                    grantPlan.concurrency(),
                    grantPlan.rpmLimit(),
                    STATUS_ACTIVE,
                    "",
                    "",
                    resolvedSignupSource
            ));
        } catch (DuplicateKeyException ex) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        try {
            authPublicEmailRepository.ensureEmailIdentity(userId, normalizedEmail, EMAIL_IDENTITY_SOURCE);
        } catch (DuplicateKeyException ignored) {
            // Best effort only. The user row is already the source of truth for email auth.
        }
        authPublicEmailRepository.ensureUserAffiliate(userId);
        assignDefaultSubscriptions(userId, grantPlan.subscriptions());
        if (invitation != null) {
            authPublicEmailRepository.markRedeemCodeUsed(invitation.id(), userId);
        }
        bindAffiliateInviterBestEffort(userId, affiliateCode);

        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findById(userId)
                .orElseThrow(() -> new ApiErrorException(503, "SERVICE_UNAVAILABLE", "service temporarily unavailable"));
        ensureUserEligible(user);
        authFlowUserRepository.touchSuccessfulLogin(user.id());
        return issueAuthTokenResponse(user, null);
    }

    @Transactional
    public AuthTokenResponse registerSyntheticOAuthAccount(
            String email,
            String username,
            String invitationCode,
            String signupSource,
            String affiliateCode
    ) {
        if (isBackendModeEnabled()) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }
        ensureRegistrationEnabled();

        String normalizedEmail = normalizeEmail(email);
        if (!isReservedEmail(normalizedEmail)) {
            throw new ApiErrorException(400, "EMAIL_NOT_RESERVED", "Email must be a reserved synthetic email for OAuth registration");
        }

        AuthPublicEmailRepository.RedeemCodeRow invitation = null;
        if (publicSettingsService.getPublicSettings().invitation_code_enabled()) {
            String normalizedInvitationCode = trimToNull(invitationCode);
            if (normalizedInvitationCode == null) {
                throw new ApiErrorException(400, "INVITATION_CODE_REQUIRED", "invitation code is required");
            }
            invitation = authPublicEmailRepository.findRedeemCodeByCodeForUpdate(normalizedInvitationCode)
                    .filter(this::isInvitationCodeUsable)
                    .orElseThrow(() -> new ApiErrorException(400, "INVITATION_CODE_INVALID", "invalid or used invitation code"));
        }

        if (authPublicEmailRepository.existsActiveUserByEmail(normalizedEmail)) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        String resolvedSignupSource = normalizeSignupSource(signupSource);
        SignupGrantPlan grantPlan = loadSignupGrantPlan(resolvedSignupSource);
        String generatedPasswordHash = passwordHasher.hash(randomHex(32));
        long userId;
        try {
            userId = authPublicEmailRepository.createUser(new AuthPublicEmailRepository.CreateUserCommand(
                    normalizedEmail,
                    generatedPasswordHash,
                    ROLE_USER,
                    grantPlan.balance(),
                    grantPlan.concurrency(),
                    grantPlan.rpmLimit(),
                    STATUS_ACTIVE,
                    normalizeOAuthUsername(username),
                    "",
                    resolvedSignupSource
            ));
        } catch (DuplicateKeyException ex) {
            throw new ApiErrorException(409, "EMAIL_EXISTS", "email already exists");
        }

        authPublicEmailRepository.ensureUserAffiliate(userId);
        assignDefaultSubscriptions(userId, grantPlan.subscriptions());
        if (invitation != null) {
            authPublicEmailRepository.markRedeemCodeUsed(invitation.id(), userId);
        }
        bindAffiliateInviterBestEffort(userId, affiliateCode);

        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findById(userId)
                .orElseThrow(() -> new ApiErrorException(503, "SERVICE_UNAVAILABLE", "service temporarily unavailable"));
        ensureUserEligible(user);
        authFlowUserRepository.touchSuccessfulLogin(user.id());
        return issueAuthTokenResponse(user, null);
    }

    @Transactional
    public void applyProviderDefaultSettingsOnFirstBind(long userId, String providerType) {
        if (userId <= 0) {
            return;
        }
        ProviderGrantSettings settings = resolveProviderGrantSettings(normalizeSignupSource(providerType), true);
        if (!settings.enabled()) {
            return;
        }
        int affected = jdbcTemplate.update("""
                insert into user_provider_default_grants (user_id, provider_type, grant_reason, granted_at, created_at)
                values (:userId, :providerType, 'first_bind', now(), now())
                on conflict (user_id, provider_type, grant_reason) do nothing
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("providerType", normalizeSignupSource(providerType)));
        if (affected == 0) {
            return;
        }
        if (settings.balance() != 0) {
            jdbcTemplate.update("""
                    update users
                    set balance = coalesce(balance, 0) + :balance,
                        updated_at = now()
                    where id = :userId
                      and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("balance", settings.balance()));
        }
        if (settings.concurrency() != 0) {
            jdbcTemplate.update("""
                    update users
                    set concurrency = coalesce(concurrency, 0) + :concurrency,
                        updated_at = now()
                    where id = :userId
                      and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("concurrency", settings.concurrency()));
        }
        assignDefaultSubscriptions(userId, settings.subscriptions());
    }

    private void verifyTurnstileForRegister(String turnstileToken, String remoteIp, String verifyCode) {
        if (publicSettingsService.getPublicSettings().email_verify_enabled() && trimToNull(verifyCode) != null) {
            return;
        }
        authTurnstileService.verify(turnstileToken, remoteIp);
    }

    private AuthMessageResponse forgotPasswordSuccess() {
        return new AuthMessageResponse("If your email is registered, you will receive a password reset link shortly.");
    }

    private void consumePasswordResetToken(String email, String token) {
        AuthPublicEmailRepository.PasswordResetTokenSession session = authPublicEmailRepository.findPasswordResetTokenSession(email);
        if (session == null || trimToNull(token) == null) {
            throw new ApiErrorException(400, "INVALID_RESET_TOKEN", "invalid or expired password reset token");
        }
        if (!constantTimeEquals(session.token(), trimToEmpty(token))) {
            throw new ApiErrorException(400, "INVALID_RESET_TOKEN", "invalid or expired password reset token");
        }
        authPublicEmailRepository.deletePasswordResetTokenSession(email);
    }

    private void verifyEmailCode(String email, String verifyCode) {
        AuthPublicEmailRepository.VerifyCodeSession session = authPublicEmailRepository.findVerifyCodeSession(email);
        if (session == null || trimToNull(verifyCode) == null) {
            throw new ApiErrorException(400, "INVALID_VERIFY_CODE", "invalid or expired verification code");
        }
        if (session.attempts() >= MAX_VERIFY_CODE_ATTEMPTS) {
            throw new ApiErrorException(429, "VERIFY_CODE_MAX_ATTEMPTS", "too many failed attempts, please request a new code");
        }
        if (!constantTimeEquals(session.code(), trimToEmpty(verifyCode))) {
            int nextAttempts = session.attempts() + 1;
            authPublicEmailRepository.saveVerifyCodeSession(email, new AuthPublicEmailRepository.VerifyCodeSession(
                    session.code(),
                    nextAttempts,
                    session.createdAt(),
                    session.expiresAt()
            ));
            if (nextAttempts >= MAX_VERIFY_CODE_ATTEMPTS) {
                throw new ApiErrorException(429, "VERIFY_CODE_MAX_ATTEMPTS", "too many failed attempts, please request a new code");
            }
            throw new ApiErrorException(400, "INVALID_VERIFY_CODE", "invalid or expired verification code");
        }
    }

    private boolean isInvitationCodeUsable(AuthPublicEmailRepository.RedeemCodeRow row) {
        return INVITATION_REDEEM_TYPE.equalsIgnoreCase(trimToEmpty(row.type()))
                && REDEEM_STATUS_UNUSED.equalsIgnoreCase(trimToEmpty(row.status()));
    }

    private void applyPromoCodeBestEffort(long userId, String promoCode) {
        if (!publicSettingsService.getPublicSettings().promo_code_enabled()) {
            return;
        }
        String normalizedPromoCode = trimToNull(promoCode);
        if (normalizedPromoCode == null) {
            return;
        }
        try {
            AuthPublicEmailRepository.PromoCodeRow promo = authPublicEmailRepository.findPromoCodeByCodeForUpdate(normalizedPromoCode)
                    .orElse(null);
            if (promo == null || !isPromoCodeUsable(promo) || authPublicEmailRepository.hasPromoCodeUsage(promo.id(), userId)) {
                return;
            }
            authPublicEmailRepository.applyPromoCode(promo.id(), userId, promo.bonusAmount());
        } catch (RuntimeException ignored) {
            log.warn("Promo code apply failed for user_id={}, code={}: {}", userId, promoCode, ignored.getMessage());
        }
    }

    private void bindAffiliateInviterBestEffort(long userId, String affiliateCode) {
        String code = normalizeAffiliateCode(affiliateCode);
        if (code == null) {
            return;
        }
        try {
            authPublicEmailRepository.ensureUserAffiliate(userId);
            if (!publicSettingsService.getPublicSettings().affiliate_enabled()) {
                return;
            }
            if (!isValidAffiliateCodeFormat(code)) {
                return;
            }
            if (authPublicEmailRepository.findAffiliateInviterIdForUpdate(userId).isPresent()) {
                return;
            }
            Long inviterId = authPublicEmailRepository.findAffiliateUserIdByCodeForUpdate(code).orElse(null);
            if (inviterId == null || inviterId <= 0 || inviterId == userId) {
                return;
            }
            authPublicEmailRepository.ensureUserAffiliate(inviterId);
            authPublicEmailRepository.bindAffiliateInviter(userId, inviterId);
        } catch (RuntimeException ignored) {
            log.warn("Affiliate inviter binding failed for user_id={}, code={}: {}", userId, code, ignored.getMessage());
        }
    }

    private boolean isValidAffiliateCodeFormat(String code) {
        if (code.length() < AFFILIATE_CODE_MIN_LENGTH || code.length() > AFFILIATE_CODE_MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            boolean valid = (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_'
                    || ch == '-';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private String normalizeAffiliateCode(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private boolean isPromoCodeUsable(AuthPublicEmailRepository.PromoCodeRow promo) {
        if (!PROMO_CODE_STATUS_ACTIVE.equalsIgnoreCase(trimToEmpty(promo.status()))) {
            return false;
        }
        if (promo.expiresAt() != null && promo.expiresAt().isBefore(OffsetDateTime.now())) {
            return false;
        }
        return promo.maxUses() <= 0 || promo.usedCount() < promo.maxUses();
    }

    private SignupGrantPlan loadSignupGrantPlan() {
        return loadSignupGrantPlan("email");
    }

    private SignupGrantPlan loadSignupGrantPlan(String signupSource) {
        MapSettings settings = loadSignupSettings();
        double balance = parseDouble(settings.get(SETTINGS_KEY_DEFAULT_BALANCE), 0);
        int concurrency = normalizePositiveInt(settings.get(SETTINGS_KEY_DEFAULT_CONCURRENCY), 5, 1);
        Integer rpmLimit = normalizeNonNegativeInteger(settings.get(SETTINGS_KEY_DEFAULT_USER_RPM_LIMIT), 0);
        List<DefaultSubscriptionSetting> subscriptions = parseDefaultSubscriptions(settings.get(SETTINGS_KEY_DEFAULT_SUBSCRIPTIONS));

        ProviderGrantSettings providerDefaults = resolveProviderGrantSettings(normalizeSignupSource(signupSource), false, settings);
        if (providerDefaults.enabled()) {
            if (providerDefaults.balance() != 0) {
                balance = providerDefaults.balance();
            }
            if (providerDefaults.concurrency() > 0 && providerDefaults.concurrency() != 5) {
                concurrency = providerDefaults.concurrency();
            }
            if (!providerDefaults.subscriptions().isEmpty()) {
                subscriptions = providerDefaults.subscriptions();
            }
        }
        return new SignupGrantPlan(balance, concurrency, rpmLimit, subscriptions);
    }

    private MapSettings loadSignupSettings() {
        return new MapSettings(authPublicEmailRepository.getSettingValues(List.of(
                SETTINGS_KEY_DEFAULT_BALANCE,
                SETTINGS_KEY_DEFAULT_CONCURRENCY,
                SETTINGS_KEY_DEFAULT_USER_RPM_LIMIT,
                SETTINGS_KEY_DEFAULT_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_BALANCE,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_CONCURRENCY,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_GRANT_ON_SIGNUP,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_GRANT_ON_FIRST_BIND,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_BALANCE,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_CONCURRENCY,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_GRANT_ON_SIGNUP,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_GRANT_ON_FIRST_BIND,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_BALANCE,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_CONCURRENCY,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_GRANT_ON_SIGNUP,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_GRANT_ON_FIRST_BIND,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_BALANCE,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_CONCURRENCY,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_GRANT_ON_SIGNUP,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_GRANT_ON_FIRST_BIND,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_BALANCE,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_CONCURRENCY,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_GRANT_ON_SIGNUP,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_GRANT_ON_FIRST_BIND,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_BALANCE,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_CONCURRENCY,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_SUBSCRIPTIONS,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_GRANT_ON_SIGNUP,
                SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_GRANT_ON_FIRST_BIND
        )));
    }

    private ProviderGrantSettings resolveProviderGrantSettings(String signupSource, boolean firstBind) {
        return resolveProviderGrantSettings(signupSource, firstBind, loadSignupSettings());
    }

    private ProviderGrantSettings resolveProviderGrantSettings(String signupSource, boolean firstBind, MapSettings settings) {
        ProviderSettingsKeySet keys = providerSettingsKeys(signupSource);
        if (keys == null) {
            return new ProviderGrantSettings(false, 0, 0, List.of(), null, null, null);
        }
        boolean enabled = firstBind
                ? isStrictTrue(settings.get(keys.grantOnFirstBind()))
                : isStrictTrue(settings.get(keys.grantOnSignup()));
        double balance = enabled ? parseDouble(settings.get(keys.balance()), 0) : 0;
        int concurrency = enabled ? normalizePositiveInt(settings.get(keys.concurrency()), 5, 0) : 0;
        List<DefaultSubscriptionSetting> subscriptions = enabled
                ? parseDefaultSubscriptionsWithFallback(settings.get(keys.subscriptions()), List.of())
                : List.of();
        return new ProviderGrantSettings(
                enabled,
                balance,
                concurrency,
                subscriptions,
                settings.get(keys.balance()),
                settings.get(keys.concurrency()),
                settings.get(keys.subscriptions())
        );
    }

    private ProviderSettingsKeySet providerSettingsKeys(String signupSource) {
        return switch (normalizeSignupSource(signupSource)) {
            case "email" -> new ProviderSettingsKeySet(
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_BALANCE,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_CONCURRENCY,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_SUBSCRIPTIONS,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_GRANT_ON_SIGNUP,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_EMAIL_GRANT_ON_FIRST_BIND
            );
            case "linuxdo" -> new ProviderSettingsKeySet(
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_BALANCE,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_CONCURRENCY,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_SUBSCRIPTIONS,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_GRANT_ON_SIGNUP,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_LINUXDO_GRANT_ON_FIRST_BIND
            );
            case "oidc" -> new ProviderSettingsKeySet(
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_BALANCE,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_CONCURRENCY,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_SUBSCRIPTIONS,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_GRANT_ON_SIGNUP,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_OIDC_GRANT_ON_FIRST_BIND
            );
            case "wechat" -> new ProviderSettingsKeySet(
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_BALANCE,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_CONCURRENCY,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_SUBSCRIPTIONS,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_GRANT_ON_SIGNUP,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_WECHAT_GRANT_ON_FIRST_BIND
            );
            case "github" -> new ProviderSettingsKeySet(
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_BALANCE,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_CONCURRENCY,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_SUBSCRIPTIONS,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_GRANT_ON_SIGNUP,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GITHUB_GRANT_ON_FIRST_BIND
            );
            case "google" -> new ProviderSettingsKeySet(
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_BALANCE,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_CONCURRENCY,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_SUBSCRIPTIONS,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_GRANT_ON_SIGNUP,
                    SETTINGS_KEY_AUTH_SOURCE_DEFAULT_GOOGLE_GRANT_ON_FIRST_BIND
            );
            default -> null;
        };
    }

    private String normalizeSignupSource(String signupSource) {
        String normalized = trimToEmpty(signupSource).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "linuxdo", "oidc", "wechat", "github", "google" -> normalized;
            default -> "email";
        };
    }

    private void assignDefaultSubscriptions(long userId, List<DefaultSubscriptionSetting> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (DefaultSubscriptionSetting item : subscriptions) {
            if (item == null || item.groupId() <= 0 || item.validityDays() <= 0) {
                continue;
            }
            if (!adminSubscriptionRepository.subscriptionGroupExists(item.groupId())) {
                continue;
            }
            adminSubscriptionRepository.createSubscription(
                    userId,
                    item.groupId(),
                    now,
                    now.plusDays(item.validityDays()),
                    STATUS_ACTIVE,
                    null,
                    now,
                    "auto assigned by signup defaults"
            );
        }
    }

    private List<DefaultSubscriptionSetting> parseDefaultSubscriptionsWithFallback(String raw, List<DefaultSubscriptionSetting> fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return parseDefaultSubscriptions(raw);
    }

    private List<DefaultSubscriptionSetting> parseDefaultSubscriptions(String raw) {
        return jsonHelper.readList(raw, DefaultSubscriptionSetting.class).stream()
                .filter(item -> item != null && item.groupId() > 0 && item.validityDays() > 0)
                .toList();
    }

    private void ensureRegistrationEnabled() {
        if (!publicSettingsService.getPublicSettings().registration_enabled()) {
            throw new ApiErrorException(403, "REGISTRATION_DISABLED", "registration is currently disabled");
        }
    }

    private void ensureNonReservedEmail(String email) {
        for (String suffix : RESERVED_EMAIL_SUFFIXES) {
            if (email.endsWith(suffix)) {
                throw new ApiErrorException(400, "EMAIL_RESERVED", "email is reserved");
            }
        }
    }

    private boolean isReservedEmail(String email) {
        if (email == null) {
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        for (String suffix : RESERVED_EMAIL_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeOAuthUsername(String username) {
        String normalized = trimToEmpty(username);
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() > 100) {
            return normalized.substring(0, 100);
        }
        return normalized;
    }

    private void ensureRegistrationEmailAllowed(String email) {
        List<String> whitelist = publicSettingsService.getPublicSettings().registration_email_suffix_whitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            return;
        }
        String lowerEmail = email.toLowerCase(Locale.ROOT);
        String domain = lowerEmail.contains("@") ? lowerEmail.substring(lowerEmail.lastIndexOf('@') + 1) : "";
        for (String suffix : whitelist) {
            String normalized = trimToNull(suffix);
            if (normalized == null) continue;
            String lowerSuffix = normalized.toLowerCase(Locale.ROOT);
            if (lowerEmail.endsWith(lowerSuffix)) {
                return;
            }
            if (lowerSuffix.startsWith("*.") && domain.length() > 0) {
                String wildcardBase = lowerSuffix.substring(1);
                if (domain.endsWith(wildcardBase) || domain.equals(wildcardBase.substring(1))) {
                    return;
                }
            }
        }
        throw new ApiErrorException(400, "EMAIL_SUFFIX_NOT_ALLOWED",
                "email suffix is not allowed, allowed suffixes: " + String.join(", ", whitelist));
    }

    private AuthTokenResponse issueAuthTokenResponse(AuthFlowUserRepository.AuthUserRow user, String familyId) {
        TokenPair pair = issueTokenPair(user, familyId);
        return new AuthTokenResponse(
                pair.accessToken(),
                pair.refreshToken(),
                pair.expiresIn(),
                TOKEN_TYPE,
                buildCurrentUserResponse(user)
        );
    }

    private TokenPair issueTokenPair(AuthFlowUserRepository.AuthUserRow user, String familyId) {
        long resolvedTokenVersion = resolvedTokenVersion(user);
        String accessToken = jwtService.issueAccessToken(
                user.id(),
                user.email(),
                trimToEmpty(user.role()),
                resolvedTokenVersion
        );

        String refreshToken = REFRESH_TOKEN_PREFIX + randomHex(32);
        String resolvedFamilyId = trimToNull(familyId);
        if (resolvedFamilyId == null) {
            resolvedFamilyId = randomHex(16);
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtService.getRefreshTokenExpireDays(), ChronoUnit.DAYS);
        authRefreshTokenRepository.store(new AuthRefreshTokenRepository.RefreshTokenRow(
                hashToken(refreshToken),
                user.id(),
                resolvedTokenVersion,
                resolvedFamilyId,
                now,
                expiresAt
        ));
        return new TokenPair(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpiresInSeconds()
        );
    }

    private CurrentUserResponse buildCurrentUserResponse(AuthFlowUserRepository.AuthUserRow user) {
        return currentUserService.getCurrentUser(new CurrentUser(
                user.id(),
                user.email(),
                user.role(),
                resolvedTokenVersion(user)
        ));
    }

    private void ensureUserEligible(AuthFlowUserRepository.AuthUserRow user) {
        if (!isActive(user)) {
            throw new ApiErrorException(403, "USER_NOT_ACTIVE", "user is not active");
        }
        if (isBackendModeEnabled() && !"admin".equalsIgnoreCase(trimToEmpty(user.role()))) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }
    }

    private boolean isTotpRequired(AuthFlowUserRepository.AuthUserRow user) {
        return publicSettingsService.getPublicSettings().totp_enabled() && user.totp_enabled();
    }

    private boolean isBackendModeEnabled() {
        return publicSettingsService.getPublicSettings().backend_mode_enabled();
    }

    private boolean isActive(AuthFlowUserRepository.AuthUserRow user) {
        return STATUS_ACTIVE.equalsIgnoreCase(trimToEmpty(user.status()));
    }

    private ApiErrorException invalidCredentials() {
        return new ApiErrorException(401, "INVALID_CREDENTIALS", "invalid email or password");
    }

    private String requireRefreshToken(String refreshToken) {
        String normalized = trimToNull(refreshToken);
        if (normalized == null || !normalized.startsWith(REFRESH_TOKEN_PREFIX)) {
            throw new ApiErrorException(401, "REFRESH_TOKEN_INVALID", "invalid refresh token");
        }
        return normalized;
    }

    private long resolvedTokenVersion(AuthFlowUserRepository.AuthUserRow user) {
        return TokenVersionResolver.resolve(user.email(), user.password_hash(), user.token_version());
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                trimToEmpty(left).getBytes(StandardCharsets.UTF_8),
                trimToEmpty(right).getBytes(StandardCharsets.UTF_8)
        );
    }

    private String hashToken(String token) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return toHex(sum);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to hash refresh token", ex);
        }
    }

    private String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        secureRandom.nextBytes(raw);
        return toHex(raw);
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private String normalizeEmail(String email) {
        String normalized = trimToEmpty(email).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String resolveSiteName() {
        String siteName = trimToNull(publicSettingsService.getPublicSettings().site_name());
        return siteName == null ? "api-private-router" : siteName;
    }

    private double parseDouble(String raw, double fallback) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int normalizePositiveInt(String raw, int fallback, int minValue) {
        Integer value = normalizeNonNegativeInteger(raw, fallback);
        if (value == null || value < minValue) {
            return fallback;
        }
        return value;
    }

    private Integer normalizeNonNegativeInteger(String raw, Integer fallback) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(normalized);
            return Math.max(parsed, 0);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean isStrictTrue(String value) {
        return "true".equalsIgnoreCase(trimToNull(value));
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

    private record TokenPair(
            String accessToken,
            String refreshToken,
            int expiresIn
    ) {
    }

    private record DefaultSubscriptionSetting(
            long groupId,
            int validityDays
    ) {
    }

    private record SignupGrantPlan(
            double balance,
            int concurrency,
            Integer rpmLimit,
            List<DefaultSubscriptionSetting> subscriptions
    ) {
    }

    private record ProviderSettingsKeySet(
            String balance,
            String concurrency,
            String subscriptions,
            String grantOnSignup,
            String grantOnFirstBind
    ) {
    }

    private record ProviderGrantSettings(
            boolean enabled,
            double balance,
            int concurrency,
            List<DefaultSubscriptionSetting> subscriptions,
            String balanceRaw,
            String concurrencyRaw,
            String subscriptionsRaw
    ) {
    }

    private record MapSettings(
            java.util.Map<String, String> values
    ) {
        String get(String key) {
            return values.get(key);
        }
    }

    public record LoginOutcome(
            AuthTokenResponse authResponse,
            TotpLoginResponse totpResponse
    ) {
        public static LoginOutcome auth(AuthTokenResponse authResponse) {
            return new LoginOutcome(authResponse, null);
        }

        public static LoginOutcome totp(TotpLoginResponse totpResponse) {
            return new LoginOutcome(null, totpResponse);
        }

        public boolean requiresTotp() {
            return totpResponse != null;
        }
    }
}
