package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthIdentityKey;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSessionView;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.PendingOAuthRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LinuxDoOAuthService {

    private static final String COOKIE_PATH = "/api/v1/auth/oauth/linuxdo";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;
    private static final String COOKIE_STATE = "linuxdo_oauth_state";
    private static final String COOKIE_VERIFIER = "linuxdo_oauth_verifier";
    private static final String COOKIE_REDIRECT = "linuxdo_oauth_redirect";
    private static final String COOKIE_INTENT = "linuxdo_oauth_intent";
    private static final String COOKIE_BIND_USER = "linuxdo_oauth_bind_user";
    private static final String COOKIE_AFFILIATE = "linuxdo_oauth_affiliate";
    private static final String DEFAULT_REDIRECT = "/dashboard";
    private static final String DEFAULT_FRONTEND_CALLBACK = "/auth/linuxdo/callback";
    private static final String OAUTH_INTENT_LOGIN = "login";
    private static final String OAUTH_INTENT_BIND_CURRENT_USER = "bind_current_user";
    private static final String PROVIDER = "linuxdo";
    private static final String SYNTHETIC_EMAIL_DOMAIN = "@linuxdo-connect.invalid";
    private static final String OIDC_SYNTHETIC_EMAIL_DOMAIN = "@oidc-connect.invalid";
    private static final String WECHAT_SYNTHETIC_EMAIL_DOMAIN = "@wechat-connect.invalid";

    private final ObjectMapper objectMapper;
    private final LinuxDoOAuthConfigService configService;
    private final PendingOAuthRepository pendingOAuthRepository;
    private final PendingOAuthCookieService pendingOAuthCookieService;
    private final OAuthBindCookieService oauthBindCookieService;
    private final AuthLifecycleService authLifecycleService;
    private final AuthPublicEmailRepository authPublicEmailRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final AuthUserRepository authUserRepository;
    private final CurrentUserContext currentUserContext;
    private final PublicSettingsService publicSettingsService;
    private final HttpClient httpClient;

    public LinuxDoOAuthService(
            ObjectMapper objectMapper,
            LinuxDoOAuthConfigService configService,
            PendingOAuthRepository pendingOAuthRepository,
            PendingOAuthCookieService pendingOAuthCookieService,
            OAuthBindCookieService oauthBindCookieService,
            AuthLifecycleService authLifecycleService,
            AuthPublicEmailRepository authPublicEmailRepository,
            JwtService jwtService,
            JwtProperties jwtProperties,
            AuthUserRepository authUserRepository,
            CurrentUserContext currentUserContext,
            PublicSettingsService publicSettingsService
    ) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.pendingOAuthRepository = pendingOAuthRepository;
        this.pendingOAuthCookieService = pendingOAuthCookieService;
        this.oauthBindCookieService = oauthBindCookieService;
        this.authLifecycleService = authLifecycleService;
        this.authPublicEmailRepository = authPublicEmailRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.authUserRepository = authUserRepository;
        this.currentUserContext = currentUserContext;
        this.publicSettingsService = publicSettingsService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public StartResult buildStartResult(HttpServletRequest request, String redirect, String affiliateCode, boolean bindCurrentUser) {
        LinuxDoOAuthConfigService.LinuxDoOAuthConfig config = configService.getRequiredConfig();
        boolean secure = pendingOAuthCookieService.isSecure(request);
        String state = randomToken();
        String redirectTo = normalizeRedirectPath(redirect);
        String browserSessionKey = randomToken();
        String intent = bindCurrentUser ? OAUTH_INTENT_BIND_CURRENT_USER : OAUTH_INTENT_LOGIN;
        String verifier = config.usePkce() ? randomToken() + randomToken() : "";
        String codeChallenge = verifier.isBlank() ? "" : sha256Url(verifier);
        ResponseCookie bindUserCookie = clearCookie(COOKIE_BIND_USER, secure);
        if (bindCurrentUser) {
            bindUserCookie = responseCookie(COOKIE_BIND_USER, encodeCookieValue(buildBindUserCookieValue(resolveBindTargetUserId(request))), secure);
        }
        return new StartResult(
                buildAuthorizeUrl(config, state, codeChallenge),
                responseCookie(COOKIE_STATE, encodeCookieValue(state), secure),
                responseCookie(COOKIE_REDIRECT, encodeCookieValue(redirectTo), secure),
                responseCookie(COOKIE_INTENT, encodeCookieValue(intent), secure),
                verifier.isBlank() ? clearCookie(COOKIE_VERIFIER, secure) : responseCookie(COOKIE_VERIFIER, encodeCookieValue(verifier), secure),
                bindUserCookie,
                affiliateCode == null || affiliateCode.trim().isEmpty()
                        ? clearCookie(COOKIE_AFFILIATE, secure)
                        : responseCookie(COOKIE_AFFILIATE, encodeCookieValue(affiliateCode.trim()), secure),
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
        LinuxDoOAuthConfigService.LinuxDoOAuthConfig config = configService.getRequiredConfig();
        String frontendCallback = firstNonBlank(config.frontendRedirectUrl(), DEFAULT_FRONTEND_CALLBACK);
        boolean secure = pendingOAuthCookieService.isSecure(request);
        if (trimToNull(error) != null) {
            return errorRedirect(frontendCallback, trimToEmpty(error), firstNonBlank(errorDescription, error), secure);
        }
        if (trimToNull(code) == null || trimToNull(state) == null) {
            return errorRedirect(frontendCallback, "missing_params", "missing code/state", secure);
        }

        String expectedState = decodeCookieValue(readCookie(request, COOKIE_STATE));
        if (expectedState.isBlank() || !MessageDigest.isEqual(expectedState.getBytes(), trimToEmpty(state).getBytes())) {
            return errorRedirect(frontendCallback, "invalid_state", "invalid oauth state", secure);
        }
        String browserSessionKey = pendingOAuthCookieService.readPendingBrowserSessionKey(request);
        if (browserSessionKey.isBlank()) {
            return errorRedirect(frontendCallback, "missing_browser_session", "missing oauth browser session", secure);
        }

        String redirectTo = normalizeRedirectPath(decodeCookieValue(readCookie(request, COOKIE_REDIRECT)));
        String intent = normalizeIntent(decodeCookieValue(readCookie(request, COOKIE_INTENT)));
        String verifier = decodeCookieValue(readCookie(request, COOKIE_VERIFIER));
        LinuxDoTokenResponse tokenResponse = exchangeCode(config, trimToEmpty(code), verifier);
        LinuxDoProfile profile = fetchProfile(config, tokenResponse);

        String compatEmail = trimToEmpty(profile.email()).toLowerCase(Locale.ROOT);
        String subject = trimToEmpty(profile.subject());
        String syntheticEmail = linuxDoSyntheticEmail(subject);
        PendingOAuthIdentityKey identityKey = new PendingOAuthIdentityKey(PROVIDER, PROVIDER, subject);
        Map<String, Object> upstreamClaims = new LinkedHashMap<>();
        upstreamClaims.put("email", syntheticEmail);
        upstreamClaims.put("username", trimToEmpty(profile.username()));
        upstreamClaims.put("subject", subject);
        upstreamClaims.put("provider", PROVIDER);
        upstreamClaims.put("provider_key", PROVIDER);
        upstreamClaims.put("provider_subject", subject);
        if (trimToNull(profile.displayName()) != null) {
            upstreamClaims.put("suggested_display_name", trimToEmpty(profile.displayName()));
        }
        if (trimToNull(profile.avatarUrl()) != null) {
            upstreamClaims.put("suggested_avatar_url", trimToEmpty(profile.avatarUrl()));
        }
        String affiliateCode = decodeCookieValue(readCookie(request, COOKIE_AFFILIATE));
        if (trimToNull(affiliateCode) != null) {
            upstreamClaims.put("aff_code", trimToEmpty(affiliateCode));
        }
        if (trimToNull(compatEmail) != null && !compatEmail.equalsIgnoreCase(syntheticEmail)) {
            upstreamClaims.put("compat_email", compatEmail);
        }

        if (OAUTH_INTENT_BIND_CURRENT_USER.equals(intent)) {
            Long targetUserId = parseBindUserCookie(decodeCookieValue(readCookie(request, COOKIE_BIND_USER)));
            if (targetUserId == null || targetUserId <= 0) {
                return errorRedirect(frontendCallback, "invalid_state", "invalid oauth bind target", secure);
            }
            PendingSessionCreation created = createPendingSession(
                    request,
                    OAUTH_INTENT_BIND_CURRENT_USER,
                    identityKey,
                    targetUserId,
                    syntheticEmail,
                    redirectTo,
                    browserSessionKey,
                    upstreamClaims,
                    Map.of("redirect", redirectTo)
            );
            return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
        }

        PendingOAuthRepository.AuthIdentityRow identityOwner = pendingOAuthRepository.findAuthIdentityOwner(identityKey).orElse(null);
        if (identityOwner != null) {
            PendingSessionCreation created = createPendingSession(
                    request,
                    OAUTH_INTENT_LOGIN,
                    identityKey,
                    identityOwner.userId(),
                    firstNonBlank(stringValue(identityOwner.metadata(), "email"), syntheticEmail),
                    redirectTo,
                    browserSessionKey,
                    upstreamClaims,
                    Map.of("redirect", redirectTo)
            );
            return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
        }

        AuthPublicEmailRepository.PublicAuthUserRow compatUser = findCompatEmailUser(compatEmail);
        Map<String, Object> completionResponse = new LinkedHashMap<>();
        completionResponse.put("step", "choose_account_action_required");
        completionResponse.put("adoption_required", true);
        completionResponse.put("redirect", redirectTo);
        completionResponse.put("email", compatUser == null ? syntheticEmail : compatUser.email());
        completionResponse.put("resolved_email", compatUser == null ? syntheticEmail : compatUser.email());
        completionResponse.put("existing_account_email", compatUser == null ? "" : compatUser.email());
        completionResponse.put("existing_account_bindable", compatUser != null);
        completionResponse.put("create_account_allowed", true);
        completionResponse.put("force_email_on_signup", publicSettingsService.getPublicSettings().force_email_on_third_party_signup());
        completionResponse.put("choice_reason", compatUser == null
                ? (publicSettingsService.getPublicSettings().force_email_on_third_party_signup() ? "force_email_on_signup" : "third_party_signup")
                : "compat_email_match");
        if (trimToNull(compatEmail) != null) {
            completionResponse.put("compat_email", compatEmail);
        }

        PendingSessionCreation created = createPendingSession(
                request,
                OAUTH_INTENT_LOGIN,
                identityKey,
                compatUser == null ? null : compatUser.id(),
                compatUser == null ? syntheticEmail : compatUser.email(),
                redirectTo,
                browserSessionKey,
                upstreamClaims,
                completionResponse
        );
        return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
    }

    public Object completeRegistration(
            HttpServletRequest request,
            String invitationCode,
            String affiliateCode
    ) {
        PendingOAuthSessionView session = requirePendingLinuxDoSession(request);
        AuthTokenResponseWithPendingBinding created = registerAndBindSyntheticAccount(
                request,
                session,
                invitationCode,
                affiliateCode
        );
        return created.response();
    }

    private AuthTokenResponseWithPendingBinding registerAndBindSyntheticAccount(
            HttpServletRequest request,
            PendingOAuthSessionView session,
            String invitationCode,
            String affiliateCode
    ) {
        String email = trimToEmpty(session.resolvedEmail());
        String username = stringValue(session.upstreamIdentityClaims(), "username");
        if (email.isBlank() || username.isBlank()) {
            throw new StructuredApiErrorException(400, "PENDING_AUTH_SESSION_INVALID", "pending auth registration context is invalid");
        }
        var created = authLifecycleService.registerSyntheticOAuthAccount(
                email,
                username,
                invitationCode,
                PROVIDER,
                firstNonBlank(trimToNull(affiliateCode), stringValue(session.upstreamIdentityClaims(), "aff_code"))
        );
        long userId = created.user().id();
        PendingOAuthIdentityKey identityKey = new PendingOAuthIdentityKey(
                trimToEmpty(session.providerType()),
                trimToEmpty(session.providerKey()),
                trimToEmpty(session.providerSubject())
        );
        pendingOAuthRepository.ensureAuthIdentityForUser(userId, identityKey, null, session.upstreamIdentityClaims());
        pendingOAuthRepository.consumePendingSession(session.id(), session.browserSessionKey())
                .orElseThrow(() -> new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used"));
        return new AuthTokenResponseWithPendingBinding(created);
    }

    private PendingOAuthSessionView requirePendingLinuxDoSession(HttpServletRequest request) {
        String sessionToken = trimToEmpty(pendingOAuthCookieService.readPendingSessionToken(request));
        if (sessionToken.isEmpty()) {
            throw new StructuredApiErrorException(404, "PENDING_AUTH_SESSION_NOT_FOUND", "pending auth session not found");
        }
        String browserSessionKey = trimToEmpty(pendingOAuthCookieService.readPendingBrowserSessionKey(request));
        if (browserSessionKey.isEmpty()) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_BROWSER_MISMATCH", "pending auth completion code does not match this browser session");
        }
        PendingOAuthSessionView session = pendingOAuthRepository.findPendingSessionByToken(sessionToken)
                .orElseThrow(() -> new StructuredApiErrorException(404, "PENDING_AUTH_SESSION_NOT_FOUND", "pending auth session not found"));
        if (!PROVIDER.equalsIgnoreCase(trimToEmpty(session.providerType()))) {
            throw new StructuredApiErrorException(400, "PENDING_AUTH_SESSION_INVALID", "pending auth registration context is invalid");
        }
        if (session.consumedAt() != null) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_CONSUMED", "pending auth session has already been used");
        }
        if (session.expiresAt() != null && Instant.now().isAfter(session.expiresAt())) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_SESSION_EXPIRED", "pending auth session has expired");
        }
        if (trimToNull(session.browserSessionKey()) != null
                && !trimToEmpty(session.browserSessionKey()).equals(browserSessionKey)) {
            throw new StructuredApiErrorException(401, "PENDING_AUTH_BROWSER_MISMATCH", "pending auth completion code does not match this browser session");
        }
        return session;
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
                PROVIDER,
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

    private LinuxDoTokenResponse exchangeCode(
            LinuxDoOAuthConfigService.LinuxDoOAuthConfig config,
            String code,
            String verifier
    ) {
        String form = "grant_type=authorization_code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(config.redirectUrl())
                + (config.usePkce() && !verifier.isBlank() ? "&code_verifier=" + urlEncode(verifier) : "")
                + ("client_secret_post".equals(config.tokenAuthMethod()) ? "&client_secret=" + urlEncode(config.clientSecret()) : "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.tokenUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if ("client_secret_basic".equals(config.tokenAuthMethod())) {
            String basic = Base64.getEncoder().encodeToString((config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            LinuxDoTokenResponse parsed = parseTokenResponse(response.body());
            if (trimToNull(parsed.accessToken()) == null) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            return parsed;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
        }
    }

    private LinuxDoTokenResponse parseTokenResponse(String body) throws JsonProcessingException {
        if (trimToNull(body) == null) {
            return new LinuxDoTokenResponse("", "Bearer", null, 0L, "");
        }
        if (body.trim().startsWith("{")) {
            LinuxDoTokenResponse parsed = objectMapper.readValue(body, LinuxDoTokenResponse.class);
            return new LinuxDoTokenResponse(
                    trimToEmpty(parsed.accessToken()),
                    firstNonBlank(parsed.tokenType(), "Bearer"),
                    trimToEmpty(parsed.refreshToken()),
                    parsed.expiresIn(),
                    trimToEmpty(parsed.scope())
            );
        }
        Map<String, String> form = parseFormBody(body);
        return new LinuxDoTokenResponse(
                trimToEmpty(form.get("access_token")),
                firstNonBlank(form.get("token_type"), "Bearer"),
                trimToEmpty(form.get("refresh_token")),
                parseLong(form.get("expires_in")),
                trimToEmpty(form.get("scope"))
        );
    }

    private LinuxDoProfile fetchProfile(LinuxDoOAuthConfigService.LinuxDoOAuthConfig config, LinuxDoTokenResponse tokenResponse) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.userInfoUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokenResponse.accessToken())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch user info");
            }
            return parseProfile(response.body(), config);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch user info");
        }
    }

    private LinuxDoProfile parseProfile(String body, LinuxDoOAuthConfigService.LinuxDoOAuthConfig config) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(body, Map.class);
        String subject = firstNonBlank(
                lookup(map, config.userInfoIdPath()),
                lookup(map, "sub"),
                lookup(map, "id"),
                lookup(map, "user_id"),
                lookup(map, "uid"),
                lookupNested(map, "user", "id")
        );
        if (!isSafeSubject(subject)) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch user info");
        }
        String email = firstNonBlank(
                lookup(map, config.userInfoEmailPath()),
                lookup(map, "email"),
                lookupNested(map, "user", "email"),
                lookupNested(map, "data", "email"),
                lookupNested(map, "attributes", "email")
        ).toLowerCase(Locale.ROOT);
        String username = firstNonBlank(
                lookup(map, config.userInfoUsernamePath()),
                lookup(map, "username"),
                lookup(map, "preferred_username"),
                lookup(map, "name"),
                lookupNested(map, "user", "username"),
                lookupNested(map, "user", "name"),
                "linuxdo_" + subject
        );
        String displayName = firstNonBlank(
                lookup(map, "name"),
                lookup(map, "nickname"),
                lookup(map, "display_name"),
                lookupNested(map, "user", "name"),
                lookupNested(map, "user", "username"),
                username
        );
        String avatarUrl = firstNonBlank(
                lookup(map, "avatar_url"),
                lookup(map, "avatar"),
                lookup(map, "picture"),
                lookup(map, "profile_image_url"),
                lookupNested(map, "user", "avatar"),
                lookupNested(map, "user", "avatar_url")
        );
        return new LinuxDoProfile(subject, email, username, displayName, avatarUrl);
    }

    private AuthPublicEmailRepository.PublicAuthUserRow findCompatEmailUser(String email) {
        String normalized = trimToEmpty(email).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
                || normalized.endsWith(SYNTHETIC_EMAIL_DOMAIN)
                || normalized.endsWith(OIDC_SYNTHETIC_EMAIL_DOMAIN)
                || normalized.endsWith(WECHAT_SYNTHETIC_EMAIL_DOMAIN)) {
            return null;
        }
        return authPublicEmailRepository.findUserByEmail(normalized).orElse(null);
    }

    private long resolveBindTargetUserId(HttpServletRequest request) {
        try {
            CurrentUser currentUser = currentUserContext.requireUser();
            if (currentUser != null && currentUser.userId() > 0) {
                return currentUser.userId();
            }
        } catch (RuntimeException ignored) {
            // Fall back to bind token cookie.
        }
        String raw = readCookie(request, OAuthBindCookieService.COOKIE_NAME);
        if (trimToNull(raw) == null) {
            throw new ApiErrorException(401, "UNAUTHORIZED", "authentication required");
        }
        String token = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        JwtUserPrincipal principal;
        try {
            principal = jwtService.parseAccessToken(token);
        } catch (RuntimeException ex) {
            throw new ApiErrorException(401, "UNAUTHORIZED", "authentication required");
        }
        return authUserRepository.findActiveUserById(principal.userId())
                .filter(user -> user.tokenVersion() == principal.tokenVersion())
                .map(CurrentUser::userId)
                .orElseThrow(() -> new ApiErrorException(401, "UNAUTHORIZED", "authentication required"));
    }

    private String buildBindUserCookieValue(long userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(configSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            return userId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception ex) {
            throw new StructuredApiErrorException(500, "OAUTH_STATE_GEN_FAILED", "failed to generate oauth state");
        }
    }

    private Long parseBindUserCookie(String value) {
        if (trimToNull(value) == null) {
            return null;
        }
        int dotIndex = value.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= value.length() - 1) {
            return null;
        }
        String payload = value.substring(0, dotIndex);
        String signature = value.substring(dotIndex + 1);
        if (!MessageDigest.isEqual(
                buildBindUserCookieValue(parseLong(payload)).getBytes(StandardCharsets.UTF_8),
                (payload + "." + signature).getBytes(StandardCharsets.UTF_8)
        )) {
            return null;
        }
        long userId = parseLong(payload);
        return userId > 0 ? userId : null;
    }

    private String configSecret() {
        String secret = firstNonBlank(jwtProperties.secret());
        if (secret.isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth bind secret not configured");
        }
        return secret;
    }

    private String buildAuthorizeUrl(LinuxDoOAuthConfigService.LinuxDoOAuthConfig config, String state, String codeChallenge) {
        StringBuilder builder = new StringBuilder(config.authorizeUrl())
                .append("?response_type=code")
                .append("&client_id=").append(urlEncode(config.clientId()))
                .append("&redirect_uri=").append(urlEncode(config.redirectUrl()))
                .append("&state=").append(urlEncode(state));
        if (!trimToEmpty(config.scopes()).isBlank()) {
            builder.append("&scope=").append(urlEncode(config.scopes()));
        }
        if (!trimToEmpty(codeChallenge).isBlank()) {
            builder.append("&code_challenge=").append(urlEncode(codeChallenge));
            builder.append("&code_challenge_method=S256");
        }
        return builder.toString();
    }

    private CallbackResult errorRedirect(String frontendCallback, String error, String description, boolean secure) {
        return new CallbackResult(
                redirectWithFragment(frontendCallback, Map.of(
                        "error", trimToEmpty(error),
                        "error_message", trimToEmpty(description),
                        "error_description", trimToEmpty(description)
                )),
                clearCookie(COOKIE_STATE, secure),
                clearCookie(COOKIE_REDIRECT, secure),
                clearCookie(COOKIE_INTENT, secure),
                clearCookie(COOKIE_VERIFIER, secure),
                clearCookie(COOKIE_BIND_USER, secure),
                clearCookie(COOKIE_AFFILIATE, secure),
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
                clearCookie(COOKIE_VERIFIER, secure),
                clearCookie(COOKIE_BIND_USER, secure),
                clearCookie(COOKIE_AFFILIATE, secure),
                sessionCookie,
                browserCookie
        );
    }

    private String redirectWithFragment(String frontendCallback, Map<String, String> fragmentValues) {
        try {
            URI uri = URI.create(firstNonBlank(frontendCallback, DEFAULT_FRONTEND_CALLBACK));
            StringBuilder fragment = new StringBuilder();
            for (Map.Entry<String, String> entry : fragmentValues.entrySet()) {
                if (fragment.length() > 0) {
                    fragment.append('&');
                }
                fragment.append(urlEncode(entry.getKey())).append('=').append(urlEncode(trimToEmpty(entry.getValue())));
            }
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), fragment.toString()).toString();
        } catch (Exception ex) {
            return DEFAULT_REDIRECT;
        }
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

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256Url(String value) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sum);
        } catch (Exception ex) {
            throw new StructuredApiErrorException(500, "OAUTH_PKCE_GEN_FAILED", "failed to generate pkce verifier");
        }
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
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return "";
    }

    private String normalizeRedirectPath(String redirect) {
        String value = trimToEmpty(redirect);
        if (value.isBlank() || !value.startsWith("/") || value.startsWith("//") || value.contains("://") || value.contains("\n") || value.contains("\r")) {
            return DEFAULT_REDIRECT;
        }
        return value;
    }

    private String normalizeIntent(String raw) {
        String normalized = trimToEmpty(raw).toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || OAUTH_INTENT_LOGIN.equals(normalized)) {
            return OAUTH_INTENT_LOGIN;
        }
        if ("bind".equals(normalized) || OAUTH_INTENT_BIND_CURRENT_USER.equals(normalized)) {
            return OAUTH_INTENT_BIND_CURRENT_USER;
        }
        return OAUTH_INTENT_LOGIN;
    }

    private String linuxDoSyntheticEmail(String subject) {
        String normalized = trimToEmpty(subject);
        return normalized.isBlank() ? "" : "linuxdo-" + normalized + SYNTHETIC_EMAIL_DOMAIN;
    }

    private boolean isSafeSubject(String subject) {
        String normalized = trimToEmpty(subject);
        if (normalized.isBlank() || normalized.length() > 56) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-')) {
                return false;
            }
        }
        return true;
    }

    private String lookup(Map<String, Object> map, String key) {
        if (map == null || trimToNull(key) == null) {
            return "";
        }
        Object value = map.get(key.trim());
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String lookupNested(Map<String, Object> map, String parent, String child) {
        Object nested = map == null ? null : map.get(parent);
        if (nested instanceof Map<?, ?> nestedMap) {
            Object value = nestedMap.get(child);
            return value == null ? "" : String.valueOf(value).trim();
        }
        return "";
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, String> parseFormBody(String body) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    private long parseLong(String raw) {
        try {
            return Long.parseLong(trimToEmpty(raw));
        } catch (NumberFormatException ex) {
            return 0L;
        }
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

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public record StartResult(
            String authorizeUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie intentCookie,
            ResponseCookie verifierCookie,
            ResponseCookie bindUserCookie,
            ResponseCookie affiliateCookie,
            ResponseCookie browserCookie,
            ResponseCookie pendingSessionCookie
    ) {
    }

    public record CallbackResult(
            String redirectUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie intentCookie,
            ResponseCookie verifierCookie,
            ResponseCookie bindUserCookie,
            ResponseCookie affiliateCookie,
            ResponseCookie pendingSessionCookie,
            ResponseCookie browserCookie
    ) {
    }

    private record PendingSessionCreation(
            ResponseCookie sessionCookie,
            ResponseCookie browserCookie
    ) {
    }

    private record LinuxDoProfile(
            String subject,
            String email,
            String username,
            String displayName,
            String avatarUrl
    ) {
    }

    private record AuthTokenResponseWithPendingBinding(
            org.apiprivaterouter.javabackend.auth.model.AuthTokenResponse response
    ) {
    }

    public record LinuxDoTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("scope") String scope
    ) {
    }
}
