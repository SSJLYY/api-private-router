package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthIdentityKey;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSessionView;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.PendingOAuthRepository;
import org.apiprivaterouter.javabackend.auth.repository.WeChatOAuthRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.security.AuthUserRepository;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.common.security.JwtProperties;
import org.apiprivaterouter.javabackend.common.security.JwtService;
import org.apiprivaterouter.javabackend.common.security.JwtUserPrincipal;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WeChatOAuthService {

    private static final String COOKIE_PATH = "/api/v1/auth/oauth/wechat";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;
    private static final String COOKIE_STATE = "wechat_oauth_state";
    private static final String COOKIE_REDIRECT = "wechat_oauth_redirect";
    private static final String COOKIE_INTENT = "wechat_oauth_intent";
    private static final String COOKIE_MODE = "wechat_oauth_mode";
    private static final String COOKIE_BIND_USER = "wechat_oauth_bind_user";
    private static final String DEFAULT_REDIRECT = "/dashboard";
    private static final String DEFAULT_FRONTEND_CALLBACK = "/auth/wechat/callback";
    private static final String PROVIDER_TYPE = "wechat";
    private static final String PROVIDER_KEY = "wechat-main";
    private static final String LEGACY_PROVIDER_KEY = "wechat";
    private static final String INTENT_LOGIN = "login";
    private static final String INTENT_BIND_CURRENT_USER = "bind_current_user";
    private static final String INTENT_ADOPT_EXISTING = "adopt_existing_user_by_email";
    private static final String SIGNING_CONTEXT = "wechat-bind-user-v1";
    private static final String AUTHORIZE_URL_OPEN = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String AUTHORIZE_URL_MP = "https://open.weixin.qq.com/connect/oauth2/authorize";
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String USER_INFO_URL = "https://api.weixin.qq.com/sns/userinfo";
    private static final String CHOICE_STEP = "choose_account_action_required";
    private static final String SYNTHETIC_EMAIL_DOMAIN = "@wechat-connect.invalid";

    private final ObjectMapper objectMapper;
    private final WeChatConnectConfigService configService;
    private final PendingOAuthRepository pendingOAuthRepository;
    private final PendingOAuthCookieService pendingOAuthCookieService;
    private final OAuthBindCookieService oauthBindCookieService;
    private final AuthPublicEmailRepository authPublicEmailRepository;
    private final AuthUserRepository authUserRepository;
    private final CurrentUserContext currentUserContext;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PublicSettingsService publicSettingsService;
    private final WeChatOAuthRepository weChatOAuthRepository;
    private final HttpClient httpClient;

    public WeChatOAuthService(
            ObjectMapper objectMapper,
            WeChatConnectConfigService configService,
            PendingOAuthRepository pendingOAuthRepository,
            PendingOAuthCookieService pendingOAuthCookieService,
            OAuthBindCookieService oauthBindCookieService,
            AuthPublicEmailRepository authPublicEmailRepository,
            AuthUserRepository authUserRepository,
            CurrentUserContext currentUserContext,
            JwtService jwtService,
            JwtProperties jwtProperties,
            PublicSettingsService publicSettingsService,
            WeChatOAuthRepository weChatOAuthRepository
    ) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.pendingOAuthRepository = pendingOAuthRepository;
        this.pendingOAuthCookieService = pendingOAuthCookieService;
        this.oauthBindCookieService = oauthBindCookieService;
        this.authPublicEmailRepository = authPublicEmailRepository;
        this.authUserRepository = authUserRepository;
        this.currentUserContext = currentUserContext;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.publicSettingsService = publicSettingsService;
        this.weChatOAuthRepository = weChatOAuthRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public StartResult buildStartResult(HttpServletRequest request, String redirect, String intent, String rawMode) {
        String mode = resolveMode(rawMode, request);
        WeChatOAuthConfig config = getRequiredConfig(request, mode);
        boolean secure = pendingOAuthCookieService.isSecure(request);
        String state = randomToken();
        String redirectTo = normalizeRedirectPath(redirect);
        String normalizedIntent = normalizeIntent(intent);
        String browserSessionKey = randomToken();
        String bindCookie = "";
        if (INTENT_BIND_CURRENT_USER.equals(normalizedIntent)) {
            bindCookie = buildBindUserCookieValue(request);
        }
        return new StartResult(
                buildAuthorizeUrl(config, state),
                responseCookie(COOKIE_STATE, encodeCookieValue(state), secure),
                responseCookie(COOKIE_REDIRECT, encodeCookieValue(redirectTo), secure),
                responseCookie(COOKIE_INTENT, encodeCookieValue(normalizedIntent), secure),
                responseCookie(COOKIE_MODE, encodeCookieValue(mode), secure),
                bindCookie.isBlank() ? clearCookie(COOKIE_BIND_USER, secure) : responseCookie(COOKIE_BIND_USER, encodeCookieValue(bindCookie), secure),
                pendingOAuthCookieService.browserCookie(browserSessionKey, secure),
                pendingOAuthCookieService.clearSessionCookie(secure)
        );
    }

    public CallbackResult handleCallback(
            HttpServletRequest request,
            String code,
            String state,
            String error,
            String errorDescription
    ) {
        boolean secure = pendingOAuthCookieService.isSecure(request);
        String frontendCallback = resolveFrontendCallback();
        if (trimToNull(error) != null) {
            return errorRedirect(frontendCallback, "provider_error", trimToEmpty(error), trimToEmpty(errorDescription), secure);
        }
        if (trimToNull(code) == null || trimToNull(state) == null) {
            return errorRedirect(frontendCallback, "missing_params", "missing code/state", "", secure);
        }

        String expectedState = decodeCookieValue(readCookie(request, COOKIE_STATE));
        if (expectedState.isBlank() || !MessageDigest.isEqual(expectedState.getBytes(), trimToEmpty(state).getBytes())) {
            return errorRedirect(frontendCallback, "invalid_state", "invalid oauth state", "", secure);
        }

        String redirectTo = normalizeRedirectPath(decodeCookieValue(readCookie(request, COOKIE_REDIRECT)));
        String browserSessionKey = pendingOAuthCookieService.readPendingBrowserSessionKey(request);
        if (browserSessionKey.isBlank()) {
            return errorRedirect(frontendCallback, "missing_browser_session", "missing oauth browser session", "", secure);
        }
        String intent = normalizeIntent(decodeCookieValue(readCookie(request, COOKIE_INTENT)));
        String mode = decodeCookieValue(readCookie(request, COOKIE_MODE));
        if (mode.isBlank()) {
            return errorRedirect(frontendCallback, "invalid_state", "missing oauth mode", "", secure);
        }

        WeChatOAuthConfig config;
        try {
            config = getRequiredConfig(request, mode);
        } catch (StructuredApiErrorException ex) {
            return errorRedirect(frontendCallback, "provider_error", ex.getReason(), ex.getMessage(), secure);
        }

        WeChatOAuthIdentity identity;
        try {
            identity = fetchIdentity(config, trimToEmpty(code));
        } catch (StructuredApiErrorException ex) {
            return errorRedirect(frontendCallback, "provider_error", "wechat_identity_fetch_failed", singleLine(ex.getMessage()), secure);
        }

        String unionId = trimToEmpty(identity.unionId());
        String openId = trimToEmpty(identity.openId());
        String providerSubject = !unionId.isBlank() ? unionId : openId;
        if (providerSubject.isBlank()) {
            return errorRedirect(frontendCallback, "provider_error", "wechat_missing_unionid", "", secure);
        }
        if (config.requiresUnionId() && unionId.isBlank()) {
            return errorRedirect(frontendCallback, "provider_error", "wechat_missing_unionid", "", secure);
        }

        String username = firstNonBlank(identity.nickname(), fallbackUsername(providerSubject));
        String email = syntheticEmail(providerSubject);
        LinkedHashMap<String, Object> upstreamClaims = new LinkedHashMap<>();
        upstreamClaims.put("email", email);
        upstreamClaims.put("username", username);
        upstreamClaims.put("subject", providerSubject);
        upstreamClaims.put("openid", openId);
        upstreamClaims.put("unionid", unionId);
        upstreamClaims.put("mode", config.mode());
        upstreamClaims.put("channel", config.mode());
        upstreamClaims.put("channel_app_id", config.appId());
        upstreamClaims.put("channel_subject", openId);
        if (!trimToEmpty(identity.nickname()).isBlank()) {
            upstreamClaims.put("suggested_display_name", trimToEmpty(identity.nickname()));
        }
        if (!trimToEmpty(identity.avatarUrl()).isBlank()) {
            upstreamClaims.put("suggested_avatar_url", trimToEmpty(identity.avatarUrl()));
        }

        if (INTENT_BIND_CURRENT_USER.equals(intent)) {
            try {
                long targetUserId = readBindUserIdFromCookie(request);
                CurrentUser targetUser = authUserRepository.findActiveUserById(targetUserId)
                        .orElseThrow(() -> new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account"));
                ensureBindOwnership(targetUser.userId(), providerSubject, config, openId);
                PendingSessionCreation created = createPendingSession(
                        request,
                        INTENT_BIND_CURRENT_USER,
                        new PendingOAuthIdentityKey(PROVIDER_TYPE, PROVIDER_KEY, providerSubject),
                        targetUser.userId(),
                        targetUser.email(),
                        redirectTo,
                        browserSessionKey,
                        upstreamClaims,
                        Map.of("redirect", redirectTo)
                );
                return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
            } catch (StructuredApiErrorException ex) {
                String kind = ex.getStatus() == 409 ? "ownership_conflict"
                        : (ex.getStatus() == 401 || ex.getStatus() == 403) ? "auth_required" : "session_error";
                return errorRedirect(frontendCallback, kind, ex.getReason(), ex.getMessage(), secure);
            } catch (ApiErrorException ex) {
                String kind = ex.getStatus() == 409 ? "ownership_conflict"
                        : (ex.getStatus() == 401 || ex.getStatus() == 403) ? "auth_required" : "session_error";
                return errorRedirect(frontendCallback, kind, ex.getReason(), ex.getMessage(), secure);
            }
        }

        try {
            AuthPublicEmailRepository.PublicAuthUserRow existingUser = resolveExistingIdentityUser(providerSubject, config, openId);
            if (existingUser != null) {
                ensureRuntimeIdentityBinding(existingUser.id(), providerSubject, upstreamClaims);
                PendingSessionCreation created = createPendingSession(
                        request,
                        INTENT_LOGIN,
                        new PendingOAuthIdentityKey(PROVIDER_TYPE, PROVIDER_KEY, providerSubject),
                        existingUser.id(),
                        existingUser.email(),
                        redirectTo,
                        browserSessionKey,
                        upstreamClaims,
                        Map.of("redirect", redirectTo)
                );
                return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
            }
        } catch (StructuredApiErrorException ex) {
            return errorRedirect(frontendCallback, "session_error", ex.getReason(), ex.getMessage(), secure);
        }

        boolean forceEmailOnSignup = publicSettingsService.getPublicSettings().force_email_on_third_party_signup();
        PendingSessionCreation created = createChoicePendingSession(
                request,
                new PendingOAuthIdentityKey(PROVIDER_TYPE, PROVIDER_KEY, providerSubject),
                email,
                redirectTo,
                browserSessionKey,
                upstreamClaims,
                forceEmailOnSignup
        );
        return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
    }

    private PendingSessionCreation createChoicePendingSession(
            HttpServletRequest request,
            PendingOAuthIdentityKey identityKey,
            String email,
            String redirectTo,
            String browserSessionKey,
            Map<String, Object> upstreamClaims,
            boolean forceEmailOnSignup
    ) {
        LinkedHashMap<String, Object> completion = new LinkedHashMap<>();
        completion.put("step", CHOICE_STEP);
        completion.put("adoption_required", true);
        completion.put("redirect", redirectTo);
        completion.put("email", email);
        completion.put("resolved_email", email);
        completion.put("existing_account_email", "");
        completion.put("existing_account_bindable", false);
        completion.put("create_account_allowed", true);
        completion.put("force_email_on_signup", forceEmailOnSignup);
        completion.put("choice_reason", forceEmailOnSignup ? "force_email_on_signup" : "third_party_signup");
        return createPendingSession(
                request,
                INTENT_LOGIN,
                identityKey,
                null,
                email,
                redirectTo,
                browserSessionKey,
                upstreamClaims,
                completion
        );
    }

    private PendingSessionCreation createPendingSession(
            HttpServletRequest request,
            String intent,
            PendingOAuthIdentityKey identityKey,
            Long targetUserId,
            String resolvedEmail,
            String redirectTo,
            String browserSessionKey,
            Map<String, Object> upstreamClaims,
            Map<String, Object> completionResponse
    ) {
        String sessionToken = randomToken() + randomToken();
        PendingOAuthSessionView session = pendingOAuthRepository.createPendingSession(new PendingOAuthRepository.CreatePendingSessionInput(
                sessionToken,
                intent,
                identityKey.providerType(),
                identityKey.providerKey(),
                identityKey.providerSubject(),
                targetUserId,
                redirectTo,
                resolvedEmail,
                "",
                upstreamClaims,
                Map.of("completion_response", completionResponse == null ? Map.of() : completionResponse),
                browserSessionKey,
                "",
                null,
                null,
                null,
                null,
                Instant.now().plus(Duration.ofMinutes(15)),
                null
        ));
        boolean secure = pendingOAuthCookieService.isSecure(request);
        return new PendingSessionCreation(
                pendingOAuthCookieService.sessionCookie(session.sessionToken(), secure),
                pendingOAuthCookieService.browserCookie(browserSessionKey, secure)
        );
    }

    @Transactional
    protected void ensureRuntimeIdentityBinding(long userId, String providerSubject, Map<String, Object> upstreamClaims) {
        String channel = trimToEmpty(stringValue(upstreamClaims, "channel"));
        String channelAppId = trimToEmpty(stringValue(upstreamClaims, "channel_app_id"));
        String channelSubject = trimToEmpty(stringValue(upstreamClaims, "channel_subject"));
        String issuer = null;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (upstreamClaims != null) {
            metadata.putAll(upstreamClaims);
        }

        List<WeChatOAuthRepository.AuthIdentityRecord> canonicalMatches = weChatOAuthRepository.findCompatibleIdentitiesBySubject(providerSubject);
        WeChatOAuthRepository.WeChatOwnerResolution identityOwner = weChatOAuthRepository.resolveSingleIdentityOwner(canonicalMatches);
        if (identityOwner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }

        WeChatOAuthRepository.AuthIdentityRecord legacyOpenIdIdentity = null;
        if (!channelSubject.isBlank() && !channelSubject.equals(providerSubject)) {
            List<WeChatOAuthRepository.AuthIdentityRecord> legacyMatches = weChatOAuthRepository.findCompatibleIdentitiesBySubject(channelSubject);
            WeChatOAuthRepository.WeChatOwnerResolution legacyOwner = weChatOAuthRepository.resolveSingleIdentityOwner(legacyMatches);
            if (legacyOwner.conflict()) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            }
            legacyOpenIdIdentity = legacyOwner.record();
        }

        long identityId;
        if (identityOwner.record() != null) {
            AuthPublicEmailRepository.PublicAuthUserRow owner = authPublicEmailRepository.findUserById(identityOwner.record().userId()).orElse(null);
            if (owner != null && "active".equalsIgnoreCase(trimToEmpty(owner.status())) && owner.id() != userId) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            }
            identityId = identityOwner.record().id();
            weChatOAuthRepository.updateIdentity(
                    identityId,
                    userId,
                    PROVIDER_KEY,
                    providerSubject,
                    issuer,
                    mergeMetadata(identityOwner.record().metadata(), metadata)
            );
        } else if (legacyOpenIdIdentity != null) {
            AuthPublicEmailRepository.PublicAuthUserRow owner = authPublicEmailRepository.findUserById(legacyOpenIdIdentity.userId()).orElse(null);
            if (owner != null && "active".equalsIgnoreCase(trimToEmpty(owner.status())) && owner.id() != userId) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            }
            identityId = legacyOpenIdIdentity.id();
            weChatOAuthRepository.updateIdentity(
                    identityId,
                    userId,
                    PROVIDER_KEY,
                    providerSubject,
                    issuer,
                    mergeMetadata(legacyOpenIdIdentity.metadata(), metadata)
            );
        } else {
            identityId = weChatOAuthRepository.upsertIdentity(userId, PROVIDER_KEY, providerSubject, issuer, metadata);
        }

        if (channel.isBlank() || channelAppId.isBlank() || channelSubject.isBlank()) {
            return;
        }

        List<WeChatOAuthRepository.AuthIdentityChannelRecord> channelMatches =
                weChatOAuthRepository.findCompatibleChannels(channel, channelAppId, channelSubject);
        WeChatOAuthRepository.WeChatChannelOwnerResolution channelOwner =
                weChatOAuthRepository.resolveSingleChannelOwner(channelMatches);
        if (channelOwner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
        }
        if (channelOwner.record() != null) {
            AuthPublicEmailRepository.PublicAuthUserRow owner = authPublicEmailRepository.findUserById(channelOwner.record().userId()).orElse(null);
            if (owner != null && "active".equalsIgnoreCase(trimToEmpty(owner.status())) && owner.id() != userId) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
            }
            weChatOAuthRepository.updateIdentityChannel(
                    channelOwner.record().id(),
                    identityId,
                    PROVIDER_KEY,
                    mergeMetadata(channelOwner.record().metadata(), metadata)
            );
            return;
        }
        weChatOAuthRepository.upsertIdentityChannel(
                identityId,
                PROVIDER_KEY,
                channel,
                channelAppId,
                channelSubject,
                metadata
        );
    }

    private AuthPublicEmailRepository.PublicAuthUserRow resolveExistingIdentityUser(
            String providerSubject,
            WeChatOAuthConfig config,
            String openId
    ) {
        List<WeChatOAuthRepository.AuthIdentityRecord> identities = weChatOAuthRepository.findCompatibleIdentitiesBySubject(providerSubject);
        WeChatOAuthRepository.WeChatOwnerResolution owner = weChatOAuthRepository.resolveSingleIdentityOwner(identities);
        if (owner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }
        if (owner.record() != null) {
            return requireActiveUser(owner.record().userId());
        }

        String channel = trimToEmpty(config.mode());
        String channelAppId = trimToEmpty(config.appId());
        if (!openId.isBlank() && !channel.isBlank() && !channelAppId.isBlank()) {
            List<WeChatOAuthRepository.AuthIdentityChannelRecord> channels =
                    weChatOAuthRepository.findCompatibleChannels(channel, channelAppId, openId);
            WeChatOAuthRepository.WeChatChannelOwnerResolution channelOwner =
                    weChatOAuthRepository.resolveSingleChannelOwner(channels);
            if (channelOwner.conflict()) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
            }
            if (channelOwner.record() != null) {
                return requireActiveUser(channelOwner.record().userId());
            }
        }

        if (openId.isBlank()) {
            return null;
        }
        List<WeChatOAuthRepository.AuthIdentityRecord> legacyOpenIdMatches = weChatOAuthRepository.findCompatibleIdentitiesBySubject(openId);
        WeChatOAuthRepository.WeChatOwnerResolution legacyOwner = weChatOAuthRepository.resolveSingleIdentityOwner(legacyOpenIdMatches);
        if (legacyOwner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }
        if (legacyOwner.record() == null) {
            return null;
        }
        return requireActiveUser(legacyOwner.record().userId());
    }

    private AuthPublicEmailRepository.PublicAuthUserRow requireActiveUser(long userId) {
        AuthPublicEmailRepository.PublicAuthUserRow user = authPublicEmailRepository.findUserById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        if (!"active".equalsIgnoreCase(trimToEmpty(user.status()))) {
            throw new StructuredApiErrorException(403, "USER_NOT_ACTIVE", "user is not active");
        }
        return user;
    }

    private void ensureBindOwnership(long userId, String providerSubject, WeChatOAuthConfig config, String channelSubject) {
        List<WeChatOAuthRepository.AuthIdentityRecord> identities = weChatOAuthRepository.findCompatibleIdentitiesBySubject(providerSubject);
        WeChatOAuthRepository.WeChatOwnerResolution owner = weChatOAuthRepository.resolveSingleIdentityOwner(identities);
        if (owner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
        }
        if (owner.record() != null && owner.record().userId() != userId) {
            AuthPublicEmailRepository.PublicAuthUserRow activeOwner = requireActiveUser(owner.record().userId());
            if (activeOwner != null) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_OWNERSHIP_CONFLICT", "auth identity already belongs to another user");
            }
        }

        String channel = trimToEmpty(config.mode());
        String channelAppId = trimToEmpty(config.appId());
        if (channelSubject.isBlank() || channel.isBlank() || channelAppId.isBlank()) {
            return;
        }
        List<WeChatOAuthRepository.AuthIdentityChannelRecord> channels =
                weChatOAuthRepository.findCompatibleChannels(channel, channelAppId, channelSubject);
        WeChatOAuthRepository.WeChatChannelOwnerResolution channelOwner =
                weChatOAuthRepository.resolveSingleChannelOwner(channels);
        if (channelOwner.conflict()) {
            throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
        }
        if (channelOwner.record() != null && channelOwner.record().userId() != userId) {
            AuthPublicEmailRepository.PublicAuthUserRow activeOwner = requireActiveUser(channelOwner.record().userId());
            if (activeOwner != null) {
                throw new StructuredApiErrorException(409, "AUTH_IDENTITY_CHANNEL_OWNERSHIP_CONFLICT", "auth identity channel already belongs to another user");
            }
        }
    }

    private WeChatOAuthIdentity fetchIdentity(WeChatOAuthConfig config, String code) {
        WeChatAccessTokenResponse tokenResponse = exchangeCode(config, code);
        WeChatUserInfoResponse userInfoResponse = fetchUserInfo(tokenResponse);
        return new WeChatOAuthIdentity(
                firstNonBlank(userInfoResponse.openId(), tokenResponse.openId()),
                firstNonBlank(userInfoResponse.unionId(), tokenResponse.unionId()),
                trimToEmpty(userInfoResponse.nickname()),
                trimToEmpty(userInfoResponse.headImgUrl())
        );
    }

    private WeChatAccessTokenResponse exchangeCode(WeChatOAuthConfig config, String code) {
        String url = ACCESS_TOKEN_URL
                + "?appid=" + urlEncode(config.appId())
                + "&secret=" + urlEncode(config.appSecret())
                + "&code=" + urlEncode(code)
                + "&grant_type=authorization_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat access token request failed");
            }
            WeChatAccessTokenResponse parsed = objectMapper.readValue(response.body(), WeChatAccessTokenResponse.class);
            if (parsed.errCode() != null && parsed.errCode() != 0) {
                throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat access token request failed");
            }
            if (trimToEmpty(parsed.accessToken()).isBlank()) {
                throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat access token missing access_token");
            }
            return parsed;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat access token request failed");
        }
    }

    private WeChatUserInfoResponse fetchUserInfo(WeChatAccessTokenResponse tokenResponse) {
        String url = USER_INFO_URL
                + "?access_token=" + urlEncode(trimToEmpty(tokenResponse.accessToken()))
                + "&openid=" + urlEncode(trimToEmpty(tokenResponse.openId()))
                + "&lang=zh_CN";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat user info request failed");
            }
            WeChatUserInfoResponse parsed = objectMapper.readValue(response.body(), WeChatUserInfoResponse.class);
            if (parsed.errCode() != null && parsed.errCode() != 0) {
                throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat user info request failed");
            }
            return parsed;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "wechat_identity_fetch_failed", "wechat user info request failed");
        }
    }

    private WeChatOAuthConfig getRequiredConfig(HttpServletRequest request, String mode) {
        WeChatConnectConfigService.WeChatConnectConfig effective = configService.getRequiredConfig();
        if (!effective.supportsMode(mode)) {
            throw new StructuredApiErrorException(404, "OAUTH_DISABLED", "wechat oauth is disabled");
        }
        String appId = trimToEmpty(effective.appIdForMode(mode));
        String appSecret = trimToEmpty(effective.appSecretForMode(mode));
        String redirectUrl = resolveCallbackUrl(effective, request);
        if (appId.isBlank() || appSecret.isBlank() || redirectUrl.isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "wechat oauth redirect url not configured");
        }
        return new WeChatOAuthConfig(
                mode,
                appId,
                appSecret,
                "mp".equals(mode) ? AUTHORIZE_URL_MP : AUTHORIZE_URL_OPEN,
                effective.scopeForMode(mode),
                redirectUrl,
                firstNonBlank(effective.frontendRedirectUrl(), DEFAULT_FRONTEND_CALLBACK),
                effective.openEnabled(),
                effective.mpEnabled()
        );
    }

    private String resolveFrontendCallback() {
        try {
            return firstNonBlank(configService.getEffectiveConfig().frontendRedirectUrl(), DEFAULT_FRONTEND_CALLBACK);
        } catch (RuntimeException ignored) {
            return DEFAULT_FRONTEND_CALLBACK;
        }
    }

    private String resolveMode(String rawMode, HttpServletRequest request) {
        String mode = trimToEmpty(rawMode).toLowerCase(Locale.ROOT);
        if (mode.isBlank()) {
            return isWeChatBrowser(request) ? "mp" : "open";
        }
        if ("open".equals(mode) || "mp".equals(mode)) {
            return mode;
        }
        throw new StructuredApiErrorException(400, "INVALID_MODE", "wechat oauth mode must be open or mp");
    }

    private boolean isWeChatBrowser(HttpServletRequest request) {
        String userAgent = trimToEmpty(request == null ? null : request.getHeader("User-Agent")).toLowerCase(Locale.ROOT);
        return userAgent.contains("micromessenger");
    }

    private String normalizeIntent(String rawIntent) {
        String value = trimToEmpty(rawIntent).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "login" -> INTENT_LOGIN;
            case "bind", "bind_current_user" -> INTENT_BIND_CURRENT_USER;
            case "adopt", "adopt_existing_user_by_email" -> INTENT_ADOPT_EXISTING;
            default -> INTENT_LOGIN;
        };
    }

    /**
     * Resolve callback URL for OAuth.
     * Priority: config.redirectUrl > config.apiBaseUrl > request headers
     *
     * SECURITY: Always configure apiBaseUrl in production to prevent X-Forwarded header spoofing.
     */
    private String resolveCallbackUrl(WeChatConnectConfigService.WeChatConnectConfig config, HttpServletRequest request) {
        if (config.redirectUrl() != null && !config.redirectUrl().isBlank()) {
            return config.redirectUrl().trim();
        }
        String apiBaseUrl = trimToEmpty(config.apiBaseUrl());
        if (!apiBaseUrl.isBlank()) {
            try {
                URI apiUri = URI.create(apiBaseUrl);
                if (apiUri.getScheme() != null && apiUri.getHost() != null) {
                    String path = apiUri.getPath() == null ? "" : apiUri.getPath();
                    if (path.endsWith("/api/v1")) {
                        return apiUri.getScheme() + "://" + apiUri.getAuthority() + path + "/auth/oauth/wechat/callback";
                    }
                    return apiUri.getScheme() + "://" + apiUri.getAuthority() + path + "/api/v1/auth/oauth/wechat/callback";
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to request-derived URL.
            }
        }
        // Fallback: use request headers (less secure, configure apiBaseUrl in production)
        String scheme = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme(), "http");
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"), request.getServerName());
        return scheme + "://" + host + "/api/v1/auth/oauth/wechat/callback";
    }

    private String buildAuthorizeUrl(WeChatOAuthConfig config, String state) {
        return config.authorizeUrl()
                + "?appid=" + urlEncode(config.appId())
                + "&redirect_uri=" + urlEncode(config.redirectUri())
                + "&response_type=code"
                + "&scope=" + urlEncode(config.scope())
                + "&state=" + urlEncode(state)
                + "#wechat_redirect";
    }

    private String buildBindUserCookieValue(HttpServletRequest request) {
        JwtUserPrincipal principal = resolveBindPrincipal(request);
        if (principal.userId() <= 0) {
            throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
        }
        String payload = principal.userId() + ":" + trimToEmpty(principal.email()) + ":" + trimToEmpty(principal.role());
        return payload + ":" + signBindUserPayload(payload);
    }

    private long readBindUserIdFromCookie(HttpServletRequest request) {
        String value = decodeCookieValue(readCookie(request, COOKIE_BIND_USER));
        if (value.isBlank()) {
            throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
        }
        String[] parts = value.split(":", 4);
        if (parts.length != 4) {
            throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
        }
        String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
        if (!MessageDigest.isEqual(
                signBindUserPayload(payload).getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
        }
        long userId;
        try {
            userId = Long.parseLong(parts[0]);
        } catch (NumberFormatException ex) {
            throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
        }
        CurrentUser user = authUserRepository.findActiveUserById(userId)
                .orElseThrow(() -> new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account"));
        return user.userId();
    }

    private JwtUserPrincipal resolveBindPrincipal(HttpServletRequest request) {
        String raw = readCookie(request, OAuthBindCookieService.COOKIE_NAME);
        if (raw == null || raw.isBlank()) {
            try {
                CurrentUser currentUser = currentUserContext.requireUser();
                return new JwtUserPrincipal(currentUser.userId(), currentUser.email(), currentUser.role(), currentUser.tokenVersion());
            } catch (RuntimeException ignored) {
                throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
            }
        }
        try {
            return jwtService.parseAccessToken(java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new ApiErrorException(401, "AUTH_REQUIRED", "current user is required to bind wechat account");
        }
    }

    private String signBindUserPayload(String payload) {
        String secret = trimToEmpty(jwtProperties.secret());
        if (secret.isBlank()) {
            throw new IllegalStateException("jwt secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((SIGNING_CONTEXT + ":" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign wechat bind payload", ex);
        }
    }

    private CallbackResult errorRedirect(String frontendCallback, String error, String errorMessage, String errorDescription, boolean secure) {
        return new CallbackResult(
                redirectWithFragment(frontendCallback, Map.of(
                        "error", trimToEmpty(error),
                        "error_message", trimToEmpty(errorMessage),
                        "error_description", trimToEmpty(errorDescription)
                )),
                clearCookie(COOKIE_STATE, secure),
                clearCookie(COOKIE_REDIRECT, secure),
                clearCookie(COOKIE_INTENT, secure),
                clearCookie(COOKIE_MODE, secure),
                clearCookie(COOKIE_BIND_USER, secure),
                null,
                null
        );
    }

    private CallbackResult pendingRedirect(String frontendCallback, ResponseCookie sessionCookie, ResponseCookie browserCookie, boolean secure) {
        return new CallbackResult(
                frontendCallback,
                clearCookie(COOKIE_STATE, secure),
                clearCookie(COOKIE_REDIRECT, secure),
                clearCookie(COOKIE_INTENT, secure),
                clearCookie(COOKIE_MODE, secure),
                clearCookie(COOKIE_BIND_USER, secure),
                sessionCookie,
                browserCookie
        );
    }

    private String redirectWithFragment(String frontendCallback, Map<String, String> fragmentValues) {
        String base = firstNonBlank(frontendCallback, DEFAULT_FRONTEND_CALLBACK);
        StringBuilder fragment = new StringBuilder();
        for (Map.Entry<String, String> entry : fragmentValues.entrySet()) {
            if (trimToEmpty(entry.getValue()).isBlank()) {
                continue;
            }
            if (fragment.length() > 0) {
                fragment.append('&');
            }
            fragment.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        if (fragment.length() == 0) {
            return base;
        }
        return base + "#" + fragment;
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

    private String normalizeRedirectPath(String path) {
        String value = trimToEmpty(path);
        if (value.isBlank() || !value.startsWith("/") || value.startsWith("//") || value.contains("://") || value.contains("\n") || value.contains("\r")) {
            return DEFAULT_REDIRECT;
        }
        return value;
    }

    private String syntheticEmail(String subject) {
        String normalized = trimToEmpty(subject);
        if (normalized.isBlank()) {
            return "";
        }
        return "wechat-" + normalized + SYNTHETIC_EMAIL_DOMAIN;
    }

    private String fallbackUsername(String subject) {
        String normalized = trimToEmpty(subject);
        if (normalized.isBlank()) {
            return "wechat_user";
        }
        return "wechat_" + normalized.substring(0, Math.min(normalized.length(), 24)).replaceAll("[^A-Za-z0-9_]", "_");
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

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String singleLine(String value) {
        return trimToEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String trimToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record StartResult(
            String authorizeUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie intentCookie,
            ResponseCookie modeCookie,
            ResponseCookie bindUserCookie,
            ResponseCookie browserCookie,
            ResponseCookie pendingSessionCookie
    ) {
    }

    public record CallbackResult(
            String redirectUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie intentCookie,
            ResponseCookie modeCookie,
            ResponseCookie bindUserCookie,
            ResponseCookie pendingSessionCookie,
            ResponseCookie browserCookie
    ) {
    }

    private record PendingSessionCreation(
            ResponseCookie sessionCookie,
            ResponseCookie browserCookie
    ) {
    }

    private record WeChatOAuthConfig(
            String mode,
            String appId,
            String appSecret,
            String authorizeUrl,
            String scope,
            String redirectUri,
            String frontendCallback,
            boolean openEnabled,
            boolean mpEnabled
    ) {
        private boolean requiresUnionId() {
            return openEnabled && mpEnabled;
        }
    }

    private record WeChatOAuthIdentity(
            String openId,
            String unionId,
            String nickname,
            String avatarUrl
    ) {
    }

    private record WeChatAccessTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("openid") String openId,
            @JsonProperty("unionid") String unionId,
            @JsonProperty("scope") String scope,
            @JsonProperty("errcode") Integer errCode,
            @JsonProperty("errmsg") String errMsg
    ) {
    }

    private record WeChatUserInfoResponse(
            @JsonProperty("openid") String openId,
            @JsonProperty("unionid") String unionId,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("headimgurl") String headImgUrl,
            @JsonProperty("errcode") Integer errCode,
            @JsonProperty("errmsg") String errMsg
    ) {
    }
}
