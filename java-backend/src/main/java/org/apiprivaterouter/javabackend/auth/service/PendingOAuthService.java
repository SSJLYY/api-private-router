package org.apiprivaterouter.javabackend.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.auth.model.AuthSendVerifyCodeResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthTokenResponse;
import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.model.OAuthAdoptionDecisionRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthBindLoginRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthCreateAccountRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthExchangeRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthIdentityKey;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSendVerifyCodeRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSessionView;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthTokenPair;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthUpsertDecisionInput;
import org.apiprivaterouter.javabackend.auth.model.TotpLoginResponse;
import org.apiprivaterouter.javabackend.auth.repository.AuthFlowUserRepository;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.PendingOAuthRepository;
import org.apiprivaterouter.javabackend.auth.repository.WeChatOAuthRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.security.PasswordHasher;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usercenter.repository.UserCenterRepository;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpService;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PendingOAuthService {

    private static final String AUTH_RESULT_PENDING_SESSION = "pending_session";
    private static final String CHOICE_STEP = "choose_account_action_required";
    private static final String INVITATION_REQUIRED = "invitation_required";

    private final PendingOAuthRepository pendingOAuthRepository;
    private final PendingOAuthCookieService cookieService;
    private final AuthLifecycleService authLifecycleService;
    private final AuthFlowUserRepository authFlowUserRepository;
    private final AuthPublicEmailRepository authPublicEmailRepository;
    private final CurrentUserService currentUserService;
    private final PublicSettingsService publicSettingsService;
    private final AuthTurnstileService authTurnstileService;
    private final UserTotpService userTotpService;
    private final UserCenterRepository userCenterRepository;
    private final PasswordHasher passwordHasher;
    private final PendingOAuthTotpSessionStore pendingOAuthTotpSessionStore;
    private final WeChatOAuthRepository weChatOAuthRepository;

    public PendingOAuthService(
            PendingOAuthRepository pendingOAuthRepository,
            PendingOAuthCookieService cookieService,
            AuthLifecycleService authLifecycleService,
            AuthFlowUserRepository authFlowUserRepository,
            AuthPublicEmailRepository authPublicEmailRepository,
            CurrentUserService currentUserService,
            PublicSettingsService publicSettingsService,
            AuthTurnstileService authTurnstileService,
            UserTotpService userTotpService,
            UserCenterRepository userCenterRepository,
            PasswordHasher passwordHasher,
            PendingOAuthTotpSessionStore pendingOAuthTotpSessionStore,
            WeChatOAuthRepository weChatOAuthRepository
    ) {
        this.pendingOAuthRepository = pendingOAuthRepository;
        this.cookieService = cookieService;
        this.authLifecycleService = authLifecycleService;
        this.authFlowUserRepository = authFlowUserRepository;
        this.authPublicEmailRepository = authPublicEmailRepository;
        this.currentUserService = currentUserService;
        this.publicSettingsService = publicSettingsService;
        this.authTurnstileService = authTurnstileService;
        this.userTotpService = userTotpService;
        this.userCenterRepository = userCenterRepository;
        this.passwordHasher = passwordHasher;
        this.pendingOAuthTotpSessionStore = pendingOAuthTotpSessionStore;
        this.weChatOAuthRepository = weChatOAuthRepository;
    }

    public CookieResult<Map<String, Object>> exchange(HttpServletRequest request, PendingOAuthExchangeRequest body) {
        PendingContext context = requirePendingBrowserSession(request);
        PendingOAuthSessionView session = context.session();
        Map<String, Object> payload = normalizeCompletionPayload(readCompletionResponse(session));
        if (payload.isEmpty()) {
            throw new StructuredApiErrorException(500, "PENDING_AUTH_COMPLETION_INVALID", "pending auth completion payload is invalid");
        }
        if (trimToNull(session.redirectTo()) != null && !payload.containsKey("redirect")) {
            payload.put("redirect", session.redirectTo().trim());
        }
        applySuggestedProfile(payload, session.upstreamIdentityClaims());
        OAuthAdoptionDecisionRequest decisionRequest = body == null ? new OAuthAdoptionDecisionRequest(null, null) : body.adoptionDecision();

        if (wantsInvitation(payload)) {
            if (decisionRequest.hasDecision()) {
                upsertDecision(session.id(), decisionRequest, null);
            }
            return new CookieResult<>(buildKeepCookiesFromContext(context, request), payload);
        }

        boolean canIssueTokenPair = canIssueTokenPair(session, payload);
        if (payload.get("adoption_required") instanceof Boolean adoptionRequired
                && adoptionRequired
                && !decisionRequest.hasDecision()) {
            return new CookieResult<>(buildKeepCookies(request), buildPendingSessionPayload(session, payload));
        }

        OAuthAdoptionDecisionRequest resolvedDecision = decisionRequest.hasDecision()
                ? decisionRequest
                : new OAuthAdoptionDecisionRequest(false, false);
        applyPendingBinding(session, resolvedDecision, session.targetUserId(), "bind_current_user".equalsIgnoreCase(trimToEmpty(session.intent())));
        pendingOAuthRepository.consumePendingSession(session.id(), session.browserSessionKey())
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));

        if (canIssueTokenPair && session.targetUserId() != null) {
            PendingOAuthTokenPair pair = authLifecycleService.issueTokenPairForUser(session.targetUserId());
            payload.put("access_token", pair.accessToken());
            payload.put("refresh_token", pair.refreshToken());
            payload.put("expires_in", pair.expiresIn());
            payload.put("token_type", pair.tokenType());
            payload.put("user", pair.user());
        }
        return new CookieResult<>(buildClearCookies(request), payload);
    }

    public CookieResult<Object> sendVerifyCode(HttpServletRequest request, PendingOAuthSendVerifyCodeRequest body) {
        authTurnstileService.verify(body.turnstileToken(), request.getRemoteAddr());
        PendingContext context = requirePendingBrowserSession(request);
        PendingOAuthSessionView session = context.session();
        ensureCompleteRegistrationSession(session);

        String email = normalizeEmail(body.email());
        AuthPublicEmailRepository.PublicAuthUserRow existingUser = authPublicEmailRepository.findUserByEmail(email).orElse(null);
        if (existingUser != null) {
            PendingOAuthSessionView updated = transitionToChoiceState(session, existingUser.id(), email);
            return new CookieResult<>(buildKeepCookies(request), buildPendingSessionPayload(updated, null));
        }
        AuthSendVerifyCodeResponse response = authLifecycleService.sendVerifyCode(email, body.turnstileToken(), request.getRemoteAddr());
        return new CookieResult<>(buildKeepCookies(request), response);
    }

    @Transactional
    public CookieResult<Object> createAccount(HttpServletRequest request, PendingOAuthCreateAccountRequest body, String provider) {
        PendingContext context = requirePendingBrowserSession(request);
        PendingOAuthSessionView session = context.session();
        ensureCompleteRegistrationSession(session);
        ensureProviderMatches(session, provider);

        String email = normalizeEmail(body.email());
        AuthPublicEmailRepository.PublicAuthUserRow existingUser = authPublicEmailRepository.findUserByEmail(email).orElse(null);
        if (existingUser != null) {
            PendingOAuthSessionView updated = transitionToChoiceState(session, existingUser.id(), email);
            return new CookieResult<>(buildKeepCookies(request), buildPendingSessionPayload(updated, null));
        }
        if (publicSettingsService.getPublicSettings().backend_mode_enabled()) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }

        AuthTokenResponse created = authLifecycleService.register(
                email,
                body.password(),
                body.verifyCode(),
                null,
                request.getRemoteAddr(),
                null,
                body.invitationCode(),
                body.affiliateCode()
        );
        long userId = created.user().id();
        applyPendingBinding(session, body.adoptionDecision(), userId, false);
        pendingOAuthRepository.consumePendingSession(session.id(), session.browserSessionKey())
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));
        return new CookieResult<>(buildClearCookies(request), created);
    }

    @Transactional
    public CookieResult<Object> bindLogin(HttpServletRequest request, PendingOAuthBindLoginRequest body, String provider) {
        PendingContext context = requirePendingBrowserSession(request);
        PendingOAuthSessionView session = context.session();
        ensureProviderMatches(session, provider);

        String email = normalizeEmail(body.email());
        AuthFlowUserRepository.AuthUserRow user = authFlowUserRepository.findByEmail(email)
                .orElseThrow(() -> new ApiErrorException(401, "INVALID_CREDENTIALS", "invalid email or password"));
        if (!BCrypt.checkpw(body.password(), user.password_hash())) {
            throw new ApiErrorException(401, "INVALID_CREDENTIALS", "invalid email or password");
        }
        if (!"active".equalsIgnoreCase(trimToEmpty(user.status()))) {
            throw new ApiErrorException(403, "USER_NOT_ACTIVE", "user is not active");
        }
        if (session.targetUserId() != null && session.targetUserId() > 0 && user.id() != session.targetUserId()) {
            throw new StructuredApiErrorException(409, "PENDING_AUTH_TARGET_USER_MISMATCH", "pending oauth session must be completed by the targeted user");
        }
        if (publicSettingsService.getPublicSettings().backend_mode_enabled() && !"admin".equalsIgnoreCase(trimToEmpty(user.role()))) {
            throw new ApiErrorException(403, "BACKEND_MODE_ADMIN_ONLY", "Backend mode is active. Only admin login is allowed.");
        }

        if (publicSettingsService.getPublicSettings().totp_enabled() && user.totp_enabled()) {
            String tempToken = userTotpService.createLoginSession(user.id(), user.email());
            pendingOAuthTotpSessionStore.save(tempToken, new PendingOAuthTotpSessionStore.PendingSessionBinding(
                    session.id(),
                    session.browserSessionKey(),
                    body.adoptionDecision(),
                    user.id()
            ));
            TotpLoginResponse response = new TotpLoginResponse(true, tempToken, userTotpService.maskEmail(user.email()));
            return new CookieResult<>(buildKeepCookies(request), response);
        }

        applyPendingBinding(session, body.adoptionDecision(), user.id(), true);
        pendingOAuthRepository.consumePendingSession(session.id(), session.browserSessionKey())
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));
        PendingOAuthTokenPair pair = authLifecycleService.issueTokenPairForUser(user.id());
        return new CookieResult<>(buildClearCookies(request), Map.of(
                "access_token", pair.accessToken(),
                "refresh_token", pair.refreshToken(),
                "expires_in", pair.expiresIn(),
                "token_type", pair.tokenType(),
                "user", pair.user()
        ));
    }

    @Transactional
    public CookieResult<Object> completeEmailOAuthRegistration(
            HttpServletRequest request,
            String provider,
            String password,
            String invitationCode,
            String affiliateCode
    ) {
        PendingContext context = requirePendingBrowserSession(request);
        PendingOAuthSessionView session = context.session();
        ensureCompleteRegistrationSession(session);
        ensureProviderMatches(session, provider);

        AuthTokenResponse created = authLifecycleService.registerVerifiedOAuthEmailAccount(
                trimToEmpty(session.resolvedEmail()),
                trimToEmpty(password),
                invitationCode,
                trimToEmpty(session.providerType()),
                firstNonBlank(trimToNull(affiliateCode), stringValue(session.upstreamIdentityClaims(), "aff_code"))
        );
        long userId = created.user().id();
        applyPendingBinding(session, new OAuthAdoptionDecisionRequest(false, false), userId, true);
        pendingOAuthRepository.consumePendingSession(session.id(), session.browserSessionKey())
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));
        return new CookieResult<>(buildClearCookies(request), created);
    }

    @Transactional
    public CookieResult<Object> completeSyntheticOAuthRegistration(
            HttpServletRequest request,
            String provider,
            String invitationCode,
            String affiliateCode,
            OAuthAdoptionDecisionRequest decisionRequest
    ) {
        PendingContext context = requirePendingBrowserSession(request);
        PendingOAuthSessionView session = context.session();
        ensureCompleteRegistrationSession(session);
        ensureProviderMatches(session, provider);

        CookieResult<Object> pendingStatus = resolveLegacyCompleteRegistrationPendingStatus(request, context, session);
        if (pendingStatus != null) {
            return pendingStatus;
        }

        String email = normalizeEmailAllowBlank(session.resolvedEmail());
        String username = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "username"));
        if (email.isBlank() || username.isBlank()) {
            throw new StructuredApiErrorException(400, "PENDING_AUTH_SESSION_INVALID", "pending auth registration context is invalid");
        }
        ensureRegistrationIdentityAvailable(session);

        AuthTokenResponse created = authLifecycleService.registerSyntheticOAuthAccount(
                email,
                username,
                invitationCode,
                trimToEmpty(session.providerType()),
                firstNonBlank(trimToNull(affiliateCode), stringValue(session.upstreamIdentityClaims(), "aff_code"))
        );
        long userId = created.user().id();
        applyPendingBinding(session, decisionRequest == null ? new OAuthAdoptionDecisionRequest(false, false) : decisionRequest, userId, false);
        pendingOAuthRepository.consumePendingSession(session.id(), session.browserSessionKey())
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));
        return new CookieResult<>(buildClearCookies(request), Map.of(
                "access_token", created.access_token(),
                "refresh_token", created.refresh_token(),
                "expires_in", created.expires_in(),
                "token_type", created.token_type()
        ));
    }

    @Transactional
    public void finalizePendingTotpLogin(long pendingSessionId, String browserSessionKey, OAuthAdoptionDecisionRequest decisionRequest, long userId) {
        PendingOAuthSessionView session = pendingOAuthRepository.findPendingSessionById(pendingSessionId)
                .orElseThrow(() -> new StructuredApiErrorException(404, "PENDING_AUTH_SESSION_NOT_FOUND", "pending auth session not found"));
        validateSession(session, browserSessionKey);
        applyPendingBinding(session, decisionRequest == null ? new OAuthAdoptionDecisionRequest(false, false) : decisionRequest, userId, true);
        pendingOAuthRepository.consumePendingSession(session.id(), browserSessionKey)
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));
    }

    private PendingContext requirePendingBrowserSession(HttpServletRequest request) {
        String sessionToken = trimToEmpty(cookieService.readPendingSessionToken(request));
        if (sessionToken.isEmpty()) {
            throw new StructuredApiErrorException(404, "PENDING_AUTH_SESSION_NOT_FOUND", "pending auth session not found");
        }
        String browserSessionKey = trimToEmpty(cookieService.readPendingBrowserSessionKey(request));
        if (browserSessionKey.isEmpty()) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_BROWSER_MISMATCH", "pending auth completion code does not match this browser session");
        }
        PendingOAuthSessionView session = pendingOAuthRepository.findPendingSessionByToken(sessionToken)
                .orElseThrow(() -> new StructuredApiErrorException(404, "PENDING_AUTH_SESSION_NOT_FOUND", "pending auth session not found"));
        validateSession(session, browserSessionKey);
        return new PendingContext(sessionToken, browserSessionKey, session);
    }

    private void validateSession(PendingOAuthSessionView session, String browserSessionKey) {
        Instant now = Instant.now();
        if (session.consumedAt() != null) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used");
        }
        if (session.expiresAt() != null && now.isAfter(session.expiresAt())) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_EXPIRED", "pending auth session has expired");
        }
        if (trimToNull(session.browserSessionKey()) != null
                && !trimToEmpty(session.browserSessionKey()).equals(trimToEmpty(browserSessionKey))) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_BROWSER_MISMATCH", "pending auth completion code does not match this browser session");
        }
    }

    private void ensureProviderMatches(PendingOAuthSessionView session, String provider) {
        if (trimToNull(provider) != null && !trimToEmpty(provider).equalsIgnoreCase(trimToEmpty(session.providerType()))) {
            throw new IllegalArgumentException("Pending oauth session provider mismatch");
        }
    }

    private void ensureCompleteRegistrationSession(PendingOAuthSessionView session) {
        if (session == null
                || !"login".equalsIgnoreCase(trimToEmpty(session.intent()))
                || session.targetUserId() != null) {
            throw new StructuredApiErrorException(400, "PENDING_AUTH_SESSION_INVALID", "pending auth registration context is invalid");
        }
        Map<String, Object> payload = readCompletionResponse(session);
        if ("bind_login_required".equalsIgnoreCase(stringValue(payload, "step"))) {
            throw new StructuredApiErrorException(400, "PENDING_AUTH_SESSION_INVALID", "pending auth registration context is invalid");
        }
    }

    private CookieResult<Object> resolveLegacyCompleteRegistrationPendingStatus(
            HttpServletRequest request,
            PendingContext context,
            PendingOAuthSessionView session
    ) {
        Map<String, Object> payload = normalizeCompletionPayload(mergeCompletionResponse(session, Map.of()));
        if (!stringValue(payload, "step").isBlank()) {
            return new CookieResult<>(buildKeepCookiesFromContext(context, request), buildPendingSessionPayload(session, payload));
        }

        boolean emailVerificationRequired = publicSettingsService.getPublicSettings().email_verify_enabled();
        boolean forceEmailOnSignup = publicSettingsService.getPublicSettings().force_email_on_third_party_signup();
        if (!emailVerificationRequired && !forceEmailOnSignup) {
            return null;
        }

        PendingOAuthSessionView updated = pendingOAuthRepository.updatePendingSessionProgress(
                session.id(),
                trimToEmpty(session.intent()),
                trimToEmpty(session.resolvedEmail()),
                null,
                buildLegacyCompleteRegistrationPendingResponse(session, forceEmailOnSignup, emailVerificationRequired)
        );
        return new CookieResult<>(buildKeepCookiesFromContext(
                new PendingContext(context.sessionToken(), context.browserSessionKey(), updated),
                request
        ), buildPendingSessionPayload(updated, null));
    }

    private Map<String, Object> buildLegacyCompleteRegistrationPendingResponse(
            PendingOAuthSessionView session,
            boolean forceEmailOnSignup,
            boolean emailVerificationRequired
    ) {
        LinkedHashMap<String, Object> completionResponse = new LinkedHashMap<>(normalizeCompletionPayload(mergeCompletionResponse(session, Map.of(
                "step", CHOICE_STEP,
                "adoption_required", true,
                "create_account_allowed", true,
                "force_email_on_signup", forceEmailOnSignup
        ))));
        if (trimToNull(session.resolvedEmail()) != null) {
            completionResponse.putIfAbsent("email", normalizeEmailAllowBlank(session.resolvedEmail()));
            completionResponse.putIfAbsent("resolved_email", normalizeEmailAllowBlank(session.resolvedEmail()));
        }
        if (!completionResponse.containsKey("choice_reason")) {
            completionResponse.put("choice_reason",
                    forceEmailOnSignup
                            ? "force_email_on_signup"
                            : emailVerificationRequired ? "email_verification_required" : "third_party_signup");
        }
        return completionResponse;
    }

    private void ensureRegistrationIdentityAvailable(PendingOAuthSessionView session) {
        if ("wechat".equalsIgnoreCase(trimToEmpty(session.providerType()))) {
            ensureWeChatRegistrationIdentityAvailable(session);
            return;
        }
        PendingOAuthIdentityKey identityKey = new PendingOAuthIdentityKey(
                trimToEmpty(session.providerType()),
                trimToEmpty(session.providerKey()),
                trimToEmpty(session.providerSubject())
        );
        PendingOAuthRepository.AuthIdentityRow identity = pendingOAuthRepository.findAuthIdentityOwner(identityKey).orElse(null);
        if (identity == null || identity.userId() <= 0) {
            return;
        }
        AuthPublicEmailRepository.PublicAuthUserRow activeOwner = authPublicEmailRepository.findUserById(identity.userId()).orElse(null);
        if (activeOwner != null && "active".equalsIgnoreCase(trimToEmpty(activeOwner.status()))) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }
    }

    private void ensureWeChatRegistrationIdentityAvailable(PendingOAuthSessionView session) {
        List<WeChatOAuthRepository.AuthIdentityRecord> identities =
                weChatOAuthRepository.findCompatibleIdentitiesBySubject(trimToEmpty(session.providerSubject()));
        WeChatOAuthRepository.WeChatOwnerResolution owner = weChatOAuthRepository.resolveSingleIdentityOwner(identities);
        if (owner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }
        if (owner.record() != null) {
            AuthPublicEmailRepository.PublicAuthUserRow activeOwner = authPublicEmailRepository.findUserById(owner.record().userId()).orElse(null);
            if (activeOwner != null && "active".equalsIgnoreCase(trimToEmpty(activeOwner.status()))) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            }
        }

        String channel = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "channel"));
        String channelAppId = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "channel_app_id"));
        String channelSubject = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "channel_subject"));
        if (channel.isBlank() || channelAppId.isBlank() || channelSubject.isBlank()) {
            return;
        }
        List<WeChatOAuthRepository.AuthIdentityChannelRecord> channels =
                weChatOAuthRepository.findCompatibleChannels(channel, channelAppId, channelSubject);
        WeChatOAuthRepository.WeChatChannelOwnerResolution channelOwner =
                weChatOAuthRepository.resolveSingleChannelOwner(channels);
        if (channelOwner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
        }
        if (channelOwner.record() != null) {
            AuthPublicEmailRepository.PublicAuthUserRow activeOwner = authPublicEmailRepository.findUserById(channelOwner.record().userId()).orElse(null);
            if (activeOwner != null && "active".equalsIgnoreCase(trimToEmpty(activeOwner.status()))) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
            }
        }
    }

    private PendingOAuthSessionView transitionToChoiceState(PendingOAuthSessionView session, long userId, String email) {
        Map<String, Object> completionResponse = mergeCompletionResponse(session, Map.of(
                "step", CHOICE_STEP,
                "adoption_required", true,
                "force_email_on_signup", true,
                "email_binding_required", true,
                "existing_account_bindable", true,
                "email", email,
                "resolved_email", email
        ));
        return pendingOAuthRepository.updatePendingSessionProgress(
                session.id(),
                trimToEmpty(session.intent()),
                email,
                userId,
                completionResponse
        );
    }

    private Map<String, Object> buildPendingSessionPayload(PendingOAuthSessionView session, Map<String, Object> preloaded) {
        Map<String, Object> completion = preloaded == null ? normalizeCompletionPayload(mergeCompletionResponse(session, Map.of())) : new LinkedHashMap<>(preloaded);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auth_result", AUTH_RESULT_PENDING_SESSION);
        payload.put("provider", trimToEmpty(session.providerType()));
        payload.put("intent", trimToEmpty(session.intent()));
        payload.putAll(completion);
        if (trimToNull(session.resolvedEmail()) != null) {
            payload.put("email", normalizeEmailAllowBlank(session.resolvedEmail()));
        }
        return payload;
    }

    private Map<String, Object> normalizeCompletionPayload(Map<String, Object> payload) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        if (payload != null) {
            normalized.putAll(payload);
        }
        normalized.remove("access_token");
        normalized.remove("refresh_token");
        normalized.remove("expires_in");
        normalized.remove("token_type");
        String step = stringValue(normalized, "step").toLowerCase(Locale.ROOT);
        switch (step) {
            case "choice", "choose_account_action", "choose_account", "choose", "email_required", "bind_login_required" ->
                    normalized.put("step", CHOICE_STEP);
            default -> {
            }
        }
        if (CHOICE_STEP.equalsIgnoreCase(stringValue(normalized, "step"))) {
            normalized.put("adoption_required", true);
        } else if (normalized.containsKey("email_binding_required") && !normalized.containsKey("adoption_required")) {
            normalized.put("adoption_required", true);
        }
        return normalized;
    }

    private Map<String, Object> readCompletionResponse(PendingOAuthSessionView session) {
        if (session == null || session.localFlowState() == null) {
            return Map.of();
        }
        Object value = session.localFlowState().get("completion_response");
        if (value instanceof Map<?, ?> rawMap) {
            LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> mapped.put(String.valueOf(key), item));
            return mapped;
        }
        return Map.of();
    }

    private Map<String, Object> mergeCompletionResponse(PendingOAuthSessionView session, Map<String, Object> overrides) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(readCompletionResponse(session));
        if (trimToNull(session.redirectTo()) != null && !merged.containsKey("redirect")) {
            merged.put("redirect", session.redirectTo().trim());
        }
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
        }
        applySuggestedProfile(merged, session.upstreamIdentityClaims());
        return merged;
    }

    private boolean wantsInvitation(Map<String, Object> payload) {
        return INVITATION_REQUIRED.equalsIgnoreCase(stringValue(payload, "error"));
    }

    private boolean canIssueTokenPair(PendingOAuthSessionView session, Map<String, Object> payload) {
        return session != null
                && "login".equalsIgnoreCase(trimToEmpty(session.intent()))
                && session.targetUserId() != null
                && session.targetUserId() > 0
                && !wantsInvitation(payload)
                && stringValue(payload, "step").isBlank();
    }

    private void applySuggestedProfile(Map<String, Object> payload, Map<String, Object> upstream) {
        if (payload == null || upstream == null || upstream.isEmpty()) {
            return;
        }
        String displayName = stringValue(upstream, "suggested_display_name");
        String avatarUrl = stringValue(upstream, "suggested_avatar_url");
        if (!displayName.isBlank() && !payload.containsKey("suggested_display_name")) {
            payload.put("suggested_display_name", displayName);
        }
        if (!avatarUrl.isBlank() && !payload.containsKey("suggested_avatar_url")) {
            payload.put("suggested_avatar_url", avatarUrl);
        }
        if (!displayName.isBlank() || !avatarUrl.isBlank()) {
            payload.put("adoption_required", true);
        }
    }

    private void applyPendingBinding(
            PendingOAuthSessionView session,
            OAuthAdoptionDecisionRequest decisionRequest,
            long userId,
            boolean applyFirstBindDefaults
    ) {
        if ("wechat".equalsIgnoreCase(trimToEmpty(session.providerType()))) {
            applyPendingWeChatBinding(session, decisionRequest, userId, applyFirstBindDefaults);
            return;
        }
        PendingOAuthIdentityKey identityKey = new PendingOAuthIdentityKey(
                trimToEmpty(session.providerType()),
                trimToEmpty(session.providerKey()),
                trimToEmpty(session.providerSubject())
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (session.upstreamIdentityClaims() != null) {
            metadata.putAll(session.upstreamIdentityClaims());
        }

        String adoptedDisplayName = decisionRequest != null && decisionRequest.resolvedAdoptDisplayName()
                ? trimToEmpty(stringValue(session.upstreamIdentityClaims(), "suggested_display_name"))
                : "";
        if (!adoptedDisplayName.isBlank()) {
            pendingOAuthRepository.updateUserUsername(userId, adoptedDisplayName);
            metadata.put("display_name", adoptedDisplayName);
        }
        String adoptedAvatarUrl = decisionRequest != null && decisionRequest.resolvedAdoptAvatar()
                ? trimToEmpty(stringValue(session.upstreamIdentityClaims(), "suggested_avatar_url"))
                : "";
        if (!adoptedAvatarUrl.isBlank()) {
            userCenterRepository.updateProfile(userId, new org.apiprivaterouter.javabackend.usercenter.model.UpdateProfileRequest(
                    null,
                    adoptedAvatarUrl,
                    null,
                    null,
                    null
            ));
            metadata.put("avatar_url", adoptedAvatarUrl);
        }

        long identityId = pendingOAuthRepository.ensureAuthIdentityForUser(userId, identityKey, null, metadata);
        long decisionId = upsertDecision(session.id(), decisionRequest == null ? new OAuthAdoptionDecisionRequest(false, false) : decisionRequest, identityId);
        pendingOAuthRepository.clearDecisionIdentityReferences(identityId, decisionId);
        pendingOAuthRepository.attachDecisionIdentity(decisionId, identityId);

        if (applyFirstBindDefaults) {
            authLifecycleService.applyProviderDefaultSettingsOnFirstBind(userId, trimToEmpty(session.providerType()));
        }
    }

    private void applyPendingWeChatBinding(
            PendingOAuthSessionView session,
            OAuthAdoptionDecisionRequest decisionRequest,
            long userId,
            boolean applyFirstBindDefaults
    ) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (session.upstreamIdentityClaims() != null) {
            metadata.putAll(session.upstreamIdentityClaims());
        }

        String adoptedDisplayName = decisionRequest != null && decisionRequest.resolvedAdoptDisplayName()
                ? trimToEmpty(stringValue(session.upstreamIdentityClaims(), "suggested_display_name"))
                : "";
        if (!adoptedDisplayName.isBlank()) {
            pendingOAuthRepository.updateUserUsername(userId, adoptedDisplayName);
            metadata.put("display_name", adoptedDisplayName);
        }
        String adoptedAvatarUrl = decisionRequest != null && decisionRequest.resolvedAdoptAvatar()
                ? trimToEmpty(stringValue(session.upstreamIdentityClaims(), "suggested_avatar_url"))
                : "";
        if (!adoptedAvatarUrl.isBlank()) {
            userCenterRepository.updateProfile(userId, new org.apiprivaterouter.javabackend.usercenter.model.UpdateProfileRequest(
                    null,
                    adoptedAvatarUrl,
                    null,
                    null,
                    null
            ));
            metadata.put("avatar_url", adoptedAvatarUrl);
        }

        long identityId = ensurePendingWeChatIdentityForUser(session, userId, metadata);
        long decisionId = upsertDecision(session.id(), decisionRequest == null ? new OAuthAdoptionDecisionRequest(false, false) : decisionRequest, identityId);
        pendingOAuthRepository.clearDecisionIdentityReferences(identityId, decisionId);
        pendingOAuthRepository.attachDecisionIdentity(decisionId, identityId);

        if (applyFirstBindDefaults) {
            authLifecycleService.applyProviderDefaultSettingsOnFirstBind(userId, trimToEmpty(session.providerType()));
        }
    }

    private long ensurePendingWeChatIdentityForUser(PendingOAuthSessionView session, long userId, Map<String, Object> metadata) {
        String providerSubject = trimToEmpty(session.providerSubject());
        String providerKey = trimToEmpty(session.providerKey());
        String channel = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "channel"));
        String channelAppId = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "channel_app_id"));
        String channelSubject = trimToEmpty(stringValue(session.upstreamIdentityClaims(), "channel_subject"));

        List<WeChatOAuthRepository.AuthIdentityRecord> canonicalMatches =
                weChatOAuthRepository.findCompatibleIdentitiesBySubject(providerSubject);
        WeChatOAuthRepository.WeChatOwnerResolution owner =
                weChatOAuthRepository.resolveSingleIdentityOwner(canonicalMatches);
        if (owner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }

        WeChatOAuthRepository.AuthIdentityRecord legacyOpenIdIdentity = null;
        if (!channelSubject.isBlank() && !channelSubject.equals(providerSubject)) {
            List<WeChatOAuthRepository.AuthIdentityRecord> legacyMatches =
                    weChatOAuthRepository.findCompatibleIdentitiesBySubject(channelSubject);
            WeChatOAuthRepository.WeChatOwnerResolution legacyOwner =
                    weChatOAuthRepository.resolveSingleIdentityOwner(legacyMatches);
            if (legacyOwner.conflict()) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            }
            legacyOpenIdIdentity = legacyOwner.record();
        }

        long identityId;
        if (owner.record() != null) {
            ensureWeChatOwnerAssignable(owner.record().userId(), userId, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            identityId = owner.record().id();
            weChatOAuthRepository.updateIdentity(
                    identityId,
                    userId,
                    firstNonBlank(providerKey, "wechat-main"),
                    providerSubject,
                    null,
                    mergeMetadata(owner.record().metadata(), metadata)
            );
        } else if (legacyOpenIdIdentity != null) {
            ensureWeChatOwnerAssignable(legacyOpenIdIdentity.userId(), userId, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            identityId = legacyOpenIdIdentity.id();
            weChatOAuthRepository.updateIdentity(
                    identityId,
                    userId,
                    firstNonBlank(providerKey, "wechat-main"),
                    providerSubject,
                    null,
                    mergeMetadata(legacyOpenIdIdentity.metadata(), metadata)
            );
        } else {
            identityId = weChatOAuthRepository.upsertIdentity(
                    userId,
                    firstNonBlank(providerKey, "wechat-main"),
                    providerSubject,
                    null,
                    metadata
            );
        }

        if (channel.isBlank() || channelAppId.isBlank() || channelSubject.isBlank()) {
            return identityId;
        }

        List<WeChatOAuthRepository.AuthIdentityChannelRecord> channelMatches =
                weChatOAuthRepository.findCompatibleChannels(channel, channelAppId, channelSubject);
        WeChatOAuthRepository.WeChatChannelOwnerResolution channelOwner =
                weChatOAuthRepository.resolveSingleChannelOwner(channelMatches);
        if (channelOwner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
        }
        if (channelOwner.record() != null) {
            ensureWeChatOwnerAssignable(channelOwner.record().userId(), userId, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
            weChatOAuthRepository.updateIdentityChannel(
                    channelOwner.record().id(),
                    identityId,
                    firstNonBlank(providerKey, "wechat-main"),
                    mergeMetadata(channelOwner.record().metadata(), metadata)
            );
        } else {
            weChatOAuthRepository.upsertIdentityChannel(
                    identityId,
                    firstNonBlank(providerKey, "wechat-main"),
                    channel,
                    channelAppId,
                    channelSubject,
                    metadata
            );
        }
        return identityId;
    }

    private void ensureWeChatOwnerAssignable(long existingUserId, long targetUserId, String error, String message) {
        if (existingUserId == targetUserId) {
            return;
        }
        AuthPublicEmailRepository.PublicAuthUserRow activeOwner = authPublicEmailRepository.findUserById(existingUserId).orElse(null);
        if (activeOwner != null && "active".equalsIgnoreCase(trimToEmpty(activeOwner.status()))) {
            throw new StructuredApiErrorException(409, error, message);
        }
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> current, Map<String, Object> incoming) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        if (incoming != null) {
            merged.putAll(incoming);
        }
        return merged;
    }

    private long upsertDecision(long sessionId, OAuthAdoptionDecisionRequest decisionRequest, Long identityId) {
        OAuthAdoptionDecisionRequest effective = decisionRequest == null
                ? new OAuthAdoptionDecisionRequest(false, false)
                : decisionRequest;
        return pendingOAuthRepository.upsertAdoptionDecision(new PendingOAuthUpsertDecisionInput(
                sessionId,
                identityId,
                effective.resolvedAdoptDisplayName(),
                effective.resolvedAdoptAvatar()
        ));
    }

    private ResponseCookie[] buildClearCookies(HttpServletRequest request) {
        boolean secure = cookieService.isSecure(request);
        return new ResponseCookie[]{
                cookieService.clearSessionCookie(secure),
                cookieService.clearBrowserCookie(secure)
        };
    }

    private ResponseCookie[] buildKeepCookies(HttpServletRequest request) {
        PendingContext context = requirePendingBrowserSession(request);
        return buildKeepCookiesFromContext(context, request);
    }

    private ResponseCookie[] buildKeepCookiesFromContext(PendingContext context, HttpServletRequest request) {
        boolean secure = cookieService.isSecure(request);
        return new ResponseCookie[]{
                cookieService.sessionCookie(context.sessionToken(), secure),
                cookieService.browserCookie(context.browserSessionKey(), secure)
        };
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : trimToEmpty(String.valueOf(value));
    }

    private String normalizeEmail(String email) {
        String normalized = trimToEmpty(email).toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || !normalized.contains("@")) {
            throw new IllegalArgumentException("invalid email");
        }
        return normalized;
    }

    private String normalizeEmailAllowBlank(String email) {
        return trimToEmpty(email).toLowerCase(Locale.ROOT);
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return "";
    }

    public record CookieResult<T>(
            ResponseCookie[] cookies,
            T body
    ) {
    }

    private record PendingContext(
            String sessionToken,
            String browserSessionKey,
            PendingOAuthSessionView session
    ) {
    }
}
