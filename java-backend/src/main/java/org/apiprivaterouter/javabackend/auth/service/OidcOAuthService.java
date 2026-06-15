package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Header;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthIdentityKey;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSessionView;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.PendingOAuthRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.security.JwtProperties;
import org.apiprivaterouter.javabackend.common.security.JwtService;
import org.apiprivaterouter.javabackend.common.security.JwtUserPrincipal;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OidcOAuthService {

    private static final String COOKIE_PATH = "/api/v1/auth/oauth/oidc";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;
    private static final String COOKIE_STATE = "oidc_oauth_state";
    private static final String COOKIE_VERIFIER = "oidc_oauth_verifier";
    private static final String COOKIE_REDIRECT = "oidc_oauth_redirect";
    private static final String COOKIE_NONCE = "oidc_oauth_nonce";
    private static final String COOKIE_INTENT = "oidc_oauth_intent";
    private static final String COOKIE_BIND_USER = "oidc_oauth_bind_user";
    private static final String DEFAULT_REDIRECT = "/dashboard";
    private static final String DEFAULT_FRONTEND_CALLBACK = "/auth/oidc/callback";
    private static final String INTENT_LOGIN = "login";
    private static final String INTENT_BIND_CURRENT_USER = "bind_current_user";
    private static final String STEP_CHOICE = "choose_account_action_required";
    private static final String SIGNING_CONTEXT = "oidc-bind-user-v1";

    private final ObjectMapper objectMapper;
    private final OidcOAuthConfigService configService;
    private final PendingOAuthRepository pendingOAuthRepository;
    private final PendingOAuthCookieService pendingOAuthCookieService;
    private final AuthLifecycleService authLifecycleService;
    private final AuthPublicEmailRepository authPublicEmailRepository;
    private final PublicSettingsService publicSettingsService;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final HttpClient httpClient;

    public OidcOAuthService(
            ObjectMapper objectMapper,
            OidcOAuthConfigService configService,
            PendingOAuthRepository pendingOAuthRepository,
            PendingOAuthCookieService pendingOAuthCookieService,
            AuthLifecycleService authLifecycleService,
            AuthPublicEmailRepository authPublicEmailRepository,
            PublicSettingsService publicSettingsService,
            JwtService jwtService,
            JwtProperties jwtProperties
    ) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.pendingOAuthRepository = pendingOAuthRepository;
        this.pendingOAuthCookieService = pendingOAuthCookieService;
        this.authLifecycleService = authLifecycleService;
        this.authPublicEmailRepository = authPublicEmailRepository;
        this.publicSettingsService = publicSettingsService;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public StartResult buildStartResult(HttpServletRequest request, String redirect, String intent) {
        OidcOAuthConfigService.OidcOAuthConfig config = configService.getRequiredConfig();
        boolean secure = pendingOAuthCookieService.isSecure(request);
        String state = randomToken();
        String redirectTo = normalizeRedirectPath(redirect);
        String browserSessionKey = randomToken();
        String normalizedIntent = normalizeIntent(intent);
        String verifier = config.usePkce() ? randomToken() + randomToken() : "";
        String codeChallenge = verifier.isBlank() ? "" : base64UrlNoPad(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
        String nonce = config.validateIdToken() ? randomToken() : "";
        String bindCookie = INTENT_BIND_CURRENT_USER.equals(normalizedIntent) ? buildBindUserCookieValue(request) : "";

        return new StartResult(
                buildAuthorizeUrl(config, state, nonce, codeChallenge),
                responseCookie(COOKIE_STATE, encodeCookieValue(state), secure),
                responseCookie(COOKIE_REDIRECT, encodeCookieValue(redirectTo), secure),
                responseCookie(COOKIE_INTENT, encodeCookieValue(normalizedIntent), secure),
                verifier.isBlank() ? clearCookie(COOKIE_VERIFIER, secure) : responseCookie(COOKIE_VERIFIER, encodeCookieValue(verifier), secure),
                nonce.isBlank() ? clearCookie(COOKIE_NONCE, secure) : responseCookie(COOKIE_NONCE, encodeCookieValue(nonce), secure),
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
        OidcOAuthConfigService.OidcOAuthConfig config = configService.getRequiredConfig();
        boolean secure = pendingOAuthCookieService.isSecure(request);
        String frontendCallback = firstNonBlank(config.frontendRedirectUrl(), DEFAULT_FRONTEND_CALLBACK);

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
        String normalizedIntent = normalizeIntent(decodeCookieValue(readCookie(request, COOKIE_INTENT)));

        String verifier = "";
        if (config.usePkce()) {
            verifier = decodeCookieValue(readCookie(request, COOKIE_VERIFIER));
            if (verifier.isBlank()) {
                return errorRedirect(frontendCallback, "missing_verifier", "missing pkce verifier", "", secure);
            }
        }
        String expectedNonce = "";
        if (config.validateIdToken()) {
            expectedNonce = decodeCookieValue(readCookie(request, COOKIE_NONCE));
            if (expectedNonce.isBlank()) {
                return errorRedirect(frontendCallback, "missing_nonce", "missing oauth nonce", "", secure);
            }
        }

        OidcTokenResponse tokenResponse = exchangeCode(config, trimToEmpty(code), verifier);
        OidcIdTokenClaims idTokenClaims = config.validateIdToken()
                ? parseAndValidateIdToken(config, tokenResponse.idToken(), expectedNonce)
                : null;
        OidcUserInfoClaims userInfo = fetchUserInfo(config, tokenResponse);

        String subject = firstNonBlank(idTokenClaims == null ? "" : idTokenClaims.subject(), userInfo.subject());
        if (subject.isBlank()) {
            return errorRedirect(frontendCallback, "missing_subject", "missing subject claim", "", secure);
        }
        String issuer = firstNonBlank(idTokenClaims == null ? "" : idTokenClaims.issuer(), config.issuerUrl());
        if (issuer.isBlank()) {
            return errorRedirect(frontendCallback, "missing_issuer", "missing issuer claim", "", secure);
        }
        if (idTokenClaims != null && !userInfo.subject().isBlank() && !subject.equals(userInfo.subject())) {
            return errorRedirect(frontendCallback, "subject_mismatch", "userinfo subject does not match id_token", "", secure);
        }

        Boolean emailVerified = userInfo.emailVerified() != null ? userInfo.emailVerified() : idTokenClaims == null ? null : idTokenClaims.emailVerified();
        if (config.requireEmailVerified() && !Boolean.TRUE.equals(emailVerified)) {
            return errorRedirect(frontendCallback, "email_not_verified", "email is not verified", "", secure);
        }

        String compatEmail = firstNonBlank(userInfo.email(), idTokenClaims == null ? "" : idTokenClaims.email()).toLowerCase(Locale.ROOT);
        String identityKey = OidcOAuthIdentityHelper.identityKey(issuer, subject);
        String syntheticEmail = OidcOAuthIdentityHelper.syntheticEmailFromIdentityKey(identityKey);
        String username = firstNonBlank(
                userInfo.username(),
                idTokenClaims == null ? "" : idTokenClaims.preferredUsername(),
                idTokenClaims == null ? "" : idTokenClaims.name(),
                OidcOAuthIdentityHelper.fallbackUsername(subject)
        );

        PendingOAuthIdentityKey identity = new PendingOAuthIdentityKey("oidc", issuer, subject);
        LinkedHashMap<String, Object> upstreamClaims = new LinkedHashMap<>();
        upstreamClaims.put("email", syntheticEmail);
        upstreamClaims.put("username", username);
        upstreamClaims.put("subject", subject);
        upstreamClaims.put("issuer", issuer);
        upstreamClaims.put("email_verified", Boolean.TRUE.equals(emailVerified));
        upstreamClaims.put("provider_fallback", trimToEmpty(config.providerName()));
        upstreamClaims.put("suggested_display_name", firstNonBlank(userInfo.displayName(), idTokenClaims == null ? "" : idTokenClaims.name(), username));
        upstreamClaims.put("suggested_avatar_url", trimToEmpty(userInfo.avatarUrl()));
        if (!compatEmail.isBlank() && !compatEmail.equalsIgnoreCase(syntheticEmail)) {
            upstreamClaims.put("compat_email", compatEmail);
        }

        if (INTENT_BIND_CURRENT_USER.equals(normalizedIntent)) {
            Long targetUserId = readBindUserIdFromCookie(request);
            if (targetUserId == null || targetUserId <= 0) {
                return errorRedirect(frontendCallback, "invalid_state", "invalid oauth bind target", "", secure);
            }
            PendingSessionCreation created = createPendingSession(
                    request,
                    normalizedIntent,
                    identity,
                    targetUserId,
                    syntheticEmail,
                    redirectTo,
                    browserSessionKey,
                    upstreamClaims,
                    Map.of("redirect", redirectTo)
            );
            return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
        }

        PendingOAuthRepository.AuthIdentityRow identityOwner = pendingOAuthRepository.findAuthIdentityOwner(identity).orElse(null);
        if (identityOwner != null) {
            AuthPublicEmailRepository.PublicAuthUserRow user = authPublicEmailRepository.findUserById(identityOwner.userId()).orElse(null);
            if (user == null) {
                return errorRedirect(frontendCallback, "session_error", "PENDING_AUTH_NOT_READY", "pending auth service is not ready", secure);
            }
            if (!"active".equalsIgnoreCase(trimToEmpty(user.status()))) {
                return errorRedirect(frontendCallback, "session_error", "USER_NOT_ACTIVE", "user is not active", secure);
            }
            PendingSessionCreation created = createPendingSession(
                    request,
                    INTENT_LOGIN,
                    identity,
                    user.id(),
                    user.email(),
                    redirectTo,
                    browserSessionKey,
                    upstreamClaims,
                    Map.of("redirect", redirectTo)
            );
            return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
        }

        AuthPublicEmailRepository.PublicAuthUserRow compatUser = findCompatEmailUser(compatEmail);
        PendingSessionCreation created = createChoicePendingSession(
                request,
                identity,
                syntheticEmail,
                redirectTo,
                browserSessionKey,
                upstreamClaims,
                compatEmail,
                compatUser
        );
        return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
    }

    private PendingSessionCreation createChoicePendingSession(
            HttpServletRequest request,
            PendingOAuthIdentityKey identity,
            String syntheticEmail,
            String redirectTo,
            String browserSessionKey,
            Map<String, Object> upstreamClaims,
            String compatEmail,
            AuthPublicEmailRepository.PublicAuthUserRow compatUser
    ) {
        boolean invitationRequired = publicSettingsService.getPublicSettings().invitation_code_enabled();
        boolean forceEmailOnSignup = publicSettingsService.getPublicSettings().force_email_on_third_party_signup();

        LinkedHashMap<String, Object> completion = new LinkedHashMap<>();
        completion.put("redirect", redirectTo);
        completion.put("step", STEP_CHOICE);
        completion.put("adoption_required", true);
        completion.put("create_account_allowed", true);
        completion.put("force_email_on_signup", forceEmailOnSignup);
        completion.put("existing_account_bindable", false);
        completion.put("existing_account_email", "");
        completion.put("email", syntheticEmail);
        completion.put("resolved_email", syntheticEmail);
        completion.put("choice_reason", "third_party_signup");
        if (!compatEmail.isBlank()) {
            completion.put("compat_email", compatEmail);
        }

        Long targetUserId = null;
        String resolvedEmail = syntheticEmail;
        if (compatUser != null) {
            targetUserId = compatUser.id();
            resolvedEmail = compatUser.email();
            completion.put("email", compatUser.email());
            completion.put("resolved_email", compatUser.email());
            completion.put("existing_account_email", compatUser.email());
            completion.put("existing_account_bindable", true);
            completion.put("choice_reason", "compat_email_match");
        } else if (invitationRequired) {
            completion.put("error", "invitation_required");
            completion.put("invitation_required", true);
            completion.put("choice_reason", "invitation_required");
        } else if (forceEmailOnSignup) {
            completion.put("choice_reason", "force_email_on_signup");
        }

        return createPendingSession(
                request,
                INTENT_LOGIN,
                identity,
                targetUserId,
                resolvedEmail,
                redirectTo,
                browserSessionKey,
                upstreamClaims,
                completion
        );
    }

    private PendingSessionCreation createPendingSession(
            HttpServletRequest request,
            String intent,
            PendingOAuthIdentityKey identity,
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
                identity.providerType(),
                identity.providerKey(),
                identity.providerSubject(),
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

    private AuthPublicEmailRepository.PublicAuthUserRow findCompatEmailUser(String email) {
        String normalized = trimToEmpty(email).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
                || OidcOAuthIdentityHelper.isReservedSyntheticEmail(normalized)
                || normalized.endsWith("@linuxdo-connect.invalid")
                || normalized.endsWith("@wechat-connect.invalid")) {
            return null;
        }
        return authPublicEmailRepository.findUserByEmail(normalized).orElse(null);
    }

    private OidcTokenResponse exchangeCode(
            OidcOAuthConfigService.OidcOAuthConfig config,
            String code,
            String verifier
    ) {
        StringBuilder form = new StringBuilder();
        appendFormValue(form, "grant_type", "authorization_code");
        appendFormValue(form, "client_id", config.clientId());
        appendFormValue(form, "code", code);
        appendFormValue(form, "redirect_uri", config.redirectUrl());
        if (!verifier.isBlank()) {
            appendFormValue(form, "code_verifier", verifier);
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(config.tokenUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
        switch (trimToEmpty(config.tokenAuthMethod()).toLowerCase(Locale.ROOT)) {
            case "", "client_secret_post" -> appendFormValue(form, "client_secret", config.clientSecret());
            case "client_secret_basic" -> requestBuilder.header("Authorization", basicAuth(config.clientId(), config.clientSecret()));
            case "none" -> {
            }
            default -> throw new StructuredApiErrorException(502, "token_exchange_failed", "unsupported token auth method");
        }
        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            OidcTokenResponse token = objectMapper.readValue(response.body(), OidcTokenResponse.class);
            if (trimToNull(token.accessToken()) == null && trimToNull(token.idToken()) == null) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "missing access token");
            }
            return token;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
        }
    }

    private OidcUserInfoClaims fetchUserInfo(OidcOAuthConfigService.OidcOAuthConfig config, OidcTokenResponse token) {
        if (trimToEmpty(config.userInfoUrl()).isBlank()) {
            return new OidcUserInfoClaims("", "", "", null, "", "");
        }
        if (trimToEmpty(token.accessToken()).isBlank()) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "missing access token");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.userInfoUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", buildBearerAuthorization(token.tokenType(), token.accessToken()))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch user info");
            }
            Map<String, Object> body = readObjectMap(response.body());
            return new OidcUserInfoClaims(
                    firstNonBlank(
                            valueAtPath(body, config.userInfoEmailPath()),
                            valueAtPath(body, "email"),
                            valueAtPath(body, "user.email"),
                            valueAtPath(body, "data.email"),
                            valueAtPath(body, "attributes.email")
                    ),
                    firstNonBlank(
                            valueAtPath(body, config.userInfoUsernamePath()),
                            valueAtPath(body, "preferred_username"),
                            valueAtPath(body, "username"),
                            valueAtPath(body, "name"),
                            valueAtPath(body, "user.username"),
                            valueAtPath(body, "user.name")
                    ),
                    firstNonBlank(
                            valueAtPath(body, config.userInfoIdPath()),
                            valueAtPath(body, "sub"),
                            valueAtPath(body, "id"),
                            valueAtPath(body, "user_id"),
                            valueAtPath(body, "uid"),
                            valueAtPath(body, "user.id")
                    ),
                    booleanAtPath(body, "email_verified"),
                    firstNonBlank(
                            valueAtPath(body, "name"),
                            valueAtPath(body, "nickname"),
                            valueAtPath(body, "display_name"),
                            valueAtPath(body, "preferred_username"),
                            valueAtPath(body, "username")
                    ),
                    firstNonBlank(
                            valueAtPath(body, "picture"),
                            valueAtPath(body, "avatar_url"),
                            valueAtPath(body, "avatar"),
                            valueAtPath(body, "profile_image_url"),
                            valueAtPath(body, "user.avatar"),
                            valueAtPath(body, "user.avatar_url")
                    )
            );
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch user info");
        }
    }

    private OidcIdTokenClaims parseAndValidateIdToken(
            OidcOAuthConfigService.OidcOAuthConfig config,
            String idToken,
            String expectedNonce
    ) {
        if (trimToNull(idToken) == null) {
            throw new StructuredApiErrorException(400, "invalid_id_token", "missing id_token");
        }
        Set<String> allowedAlgs = allowedSigningAlgs(config.allowedSigningAlgs());
        Map<String, JwkKey> jwkKeys = fetchJwkKeys(config.jwksUrl(), allowedAlgs);
        Claims claims;
        try {
            claims = Jwts.parser()
                    .clockSkewSeconds(config.clockSkewSeconds())
                    .requireIssuer(config.issuerUrl())
                    .keyLocator(header -> resolveVerificationKey(header, jwkKeys, allowedAlgs))
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (Exception ex) {
            throw new StructuredApiErrorException(400, "invalid_id_token", "failed to validate id_token");
        }

        List<String> audience = normalizeAudienceClaim(claims.get("aud"));
        if (audience.stream().noneMatch(item -> trimToEmpty(config.clientId()).equals(trimToEmpty(item)))) {
            throw new StructuredApiErrorException(400, "invalid_id_token", "id_token audience mismatch");
        }
        String nonce = claims.get("nonce", String.class);
        if (!trimToEmpty(expectedNonce).isBlank() && !trimToEmpty(expectedNonce).equals(trimToEmpty(nonce))) {
            throw new StructuredApiErrorException(400, "invalid_id_token", "id_token nonce mismatch");
        }
        String azp = claims.get("azp", String.class);
        if (audience.size() > 1 && !trimToEmpty(config.clientId()).equals(trimToEmpty(azp))) {
            throw new StructuredApiErrorException(400, "invalid_id_token", "id_token azp mismatch");
        }
        return new OidcIdTokenClaims(
                trimToEmpty(claims.getSubject()),
                trimToEmpty(claims.getIssuer()),
                trimToEmpty(claims.get("email", String.class)),
                booleanClaim(claims.get("email_verified")),
                trimToEmpty(claims.get("preferred_username", String.class)),
                trimToEmpty(claims.get("name", String.class)),
                trimToEmpty(nonce),
                trimToEmpty(azp)
        );
    }

    private Map<String, JwkKey> fetchJwkKeys(String jwksUrl, Set<String> allowedAlgs) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "jwks request failed");
            }
            OidcJwkSet set = objectMapper.readValue(response.body(), OidcJwkSet.class);
            LinkedHashMap<String, JwkKey> mapped = new LinkedHashMap<>();
            int index = 0;
            if (set.keys() != null) {
                for (OidcJwk jwk : set.keys()) {
                    if (jwk == null) {
                        continue;
                    }
                    String use = trimToEmpty(jwk.use());
                    if (!use.isBlank() && !"sig".equalsIgnoreCase(use)) {
                        continue;
                    }
                    String alg = trimToEmpty(jwk.alg()).toUpperCase(Locale.ROOT);
                    if (!alg.isBlank() && !allowedAlgs.contains(alg)) {
                        continue;
                    }
                    String kid = trimToEmpty(jwk.kid());
                    mapped.put(kid.isBlank() ? "__index_" + index++ : kid, new JwkKey(alg, toVerificationKey(jwk)));
                }
            }
            if (mapped.isEmpty()) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "jwks is empty");
            }
            return mapped;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "jwks request failed");
        }
    }

    private Key resolveVerificationKey(Header header, Map<String, JwkKey> jwkKeys, Set<String> allowedAlgs) {
        String alg = trimToEmpty(String.valueOf(header.get("alg"))).toUpperCase(Locale.ROOT);
        if (!allowedAlgs.contains(alg)) {
            throw new StructuredApiErrorException(400, "invalid_id_token", "unexpected signing algorithm");
        }
        Object kidValue = header.get("kid");
        String kid = trimToEmpty(kidValue == null ? "" : String.valueOf(kidValue));
        if (!kid.isBlank()) {
            JwkKey key = jwkKeys.get(kid);
            if (key == null) {
                throw new StructuredApiErrorException(400, "invalid_id_token", "jwk not found");
            }
            if (!key.algorithm().isBlank() && !alg.equalsIgnoreCase(key.algorithm())) {
                throw new StructuredApiErrorException(400, "invalid_id_token", "jwk algorithm mismatch");
            }
            return key.key();
        }
        for (JwkKey key : jwkKeys.values()) {
            if (key.algorithm().isBlank() || alg.equalsIgnoreCase(key.algorithm())) {
                return key.key();
            }
        }
        throw new StructuredApiErrorException(400, "invalid_id_token", "jwk not found");
    }

    private Key toVerificationKey(OidcJwk jwk) {
        try {
            return switch (trimToEmpty(jwk.kty()).toUpperCase(Locale.ROOT)) {
                case "RSA" -> rsaKey(jwk);
                case "EC" -> ecKey(jwk);
                default -> throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "unsupported jwk key type");
            };
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "invalid jwk");
        }
    }

    private RSAPublicKey rsaKey(OidcJwk jwk) throws Exception {
        BigInteger modulus = decodeBigInt(jwk.n());
        byte[] exponentBytes = Base64.getUrlDecoder().decode(padBase64(trimToEmpty(jwk.e())));
        int exponent = 0;
        for (byte value : exponentBytes) {
            exponent = (exponent << 8) | (value & 0xff);
        }
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent)));
    }

    private Key ecKey(OidcJwk jwk) throws Exception {
        String namedCurve = switch (trimToEmpty(jwk.crv())) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> null;
        };
        if (namedCurve == null) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "unsupported ec curve");
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(namedCurve));
        ECParameterSpec params = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger x = decodeBigInt(jwk.x());
        BigInteger y = decodeBigInt(jwk.y());
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    private String buildAuthorizeUrl(
            OidcOAuthConfigService.OidcOAuthConfig config,
            String state,
            String nonce,
            String codeChallenge
    ) {
        StringBuilder builder = new StringBuilder(config.authorizeUrl());
        builder.append(config.authorizeUrl().contains("?") ? "&" : "?")
                .append("response_type=code")
                .append("&client_id=").append(urlEncode(config.clientId()))
                .append("&redirect_uri=").append(urlEncode(config.redirectUrl()))
                .append("&scope=").append(urlEncode(config.scopes()))
                .append("&state=").append(urlEncode(state));
        if (!nonce.isBlank()) {
            builder.append("&nonce=").append(urlEncode(nonce));
        }
        if (!codeChallenge.isBlank()) {
            builder.append("&code_challenge=").append(urlEncode(codeChallenge))
                    .append("&code_challenge_method=S256");
        }
        return builder.toString();
    }

    private String buildBindUserCookieValue(HttpServletRequest request) {
        JwtUserPrincipal principal = resolveBindPrincipal(request);
        if (principal.userId() <= 0) {
            throw new ApiErrorException(401, "AUTHENTICATION_REQUIRED", "authentication required");
        }
        String payload = principal.userId() + ":" + trimToEmpty(principal.email()) + ":" + trimToEmpty(principal.role());
        return payload + ":" + signBindUserPayload(payload);
    }

    private Long readBindUserIdFromCookie(HttpServletRequest request) {
        String value = decodeCookieValue(readCookie(request, COOKIE_BIND_USER));
        if (value.isBlank()) {
            return null;
        }
        String[] parts = value.split(":", 4);
        if (parts.length != 4) {
            return null;
        }
        String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
        if (!MessageDigest.isEqual(
                signBindUserPayload(payload).getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            return Long.parseLong(parts[0]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JwtUserPrincipal resolveBindPrincipal(HttpServletRequest request) {
        String raw = readCookie(request, OAuthBindCookieService.COOKIE_NAME);
        if (raw == null || raw.isBlank()) {
            throw new ApiErrorException(401, "AUTHENTICATION_REQUIRED", "authentication required");
        }
        try {
            return jwtService.parseAccessToken(java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new ApiErrorException(401, "AUTHENTICATION_REQUIRED", "authentication required");
        }
    }

    private String signBindUserPayload(String payload) {
        String secret = trimToEmpty(jwtProperties.secret());
        if (secret.isBlank()) {
            throw new IllegalStateException("jwt secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((SIGNING_CONTEXT + "\n" + payload).getBytes(StandardCharsets.UTF_8));
            return base64UrlNoPad(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign oidc bind payload", ex);
        }
    }

    private CallbackResult errorRedirect(String frontendCallback, String error, String errorMessage, String errorDescription, boolean secure) {
        LinkedHashMap<String, String> fragment = new LinkedHashMap<>();
        fragment.put("error", trimToEmpty(error));
        if (!trimToEmpty(errorMessage).isBlank()) {
            fragment.put("error_message", trimToEmpty(errorMessage));
        }
        if (!trimToEmpty(errorDescription).isBlank()) {
            fragment.put("error_description", trimToEmpty(errorDescription));
        }
        return new CallbackResult(
                redirectWithFragment(frontendCallback, fragment),
                clearCookie(COOKIE_STATE, secure),
                clearCookie(COOKIE_REDIRECT, secure),
                clearCookie(COOKIE_VERIFIER, secure),
                clearCookie(COOKIE_NONCE, secure),
                clearCookie(COOKIE_INTENT, secure),
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
                clearCookie(COOKIE_VERIFIER, secure),
                clearCookie(COOKIE_NONCE, secure),
                clearCookie(COOKIE_INTENT, secure),
                clearCookie(COOKIE_BIND_USER, secure),
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
            return DEFAULT_FRONTEND_CALLBACK;
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

    private Set<String> allowedSigningAlgs(String raw) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : firstNonBlank(raw, "RS256,ES256,PS256").split(",")) {
            String normalized = trimToEmpty(part).toUpperCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private List<String> normalizeAudienceClaim(Object aud) {
        if (aud == null) {
            return List.of();
        }
        if (aud instanceof List<?> list) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (Object item : list) {
                String normalized = trimToEmpty(item == null ? null : String.valueOf(item));
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values;
        }
        String normalized = trimToEmpty(String.valueOf(aud));
        return normalized.isBlank() ? List.of() : List.of(normalized);
    }

    private String buildBearerAuthorization(String tokenType, String accessToken) {
        String normalizedType = trimToEmpty(tokenType);
        if (normalizedType.isBlank()) {
            normalizedType = "Bearer";
        }
        return normalizedType + " " + trimToEmpty(accessToken);
    }

    private Map<String, Object> readObjectMap(String body) {
        try {
            Map<?, ?> raw = objectMapper.readValue(body, Map.class);
            LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
            raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
            return mapped;
        } catch (IOException ex) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to parse user info");
        }
    }

    private String valueAtPath(Map<String, Object> values, String path) {
        String normalized = trimToEmpty(path);
        if (values == null || values.isEmpty() || normalized.isBlank()) {
            return "";
        }
        Object current = values;
        for (String segment : normalized.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(segment);
            if (current == null) {
                return "";
            }
        }
        return String.valueOf(current).trim();
    }

    private Boolean booleanAtPath(Map<String, Object> values, String path) {
        String normalized = trimToEmpty(path);
        if (values == null || values.isEmpty() || normalized.isBlank()) {
            return null;
        }
        Object current = values;
        for (String segment : normalized.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        if (current instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(current).trim();
        return text.isBlank() ? null : Boolean.parseBoolean(text);
    }

    private Boolean booleanClaim(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = trimToEmpty(value == null ? null : String.valueOf(value));
        return normalized.isBlank() ? null : Boolean.parseBoolean(normalized);
    }

    private void appendFormValue(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(urlEncode(key)).append('=').append(urlEncode(value));
    }

    private String basicAuth(String clientId, String clientSecret) {
        return "Basic " + Base64.getEncoder().encodeToString((trimToEmpty(clientId) + ":" + trimToEmpty(clientSecret)).getBytes(StandardCharsets.UTF_8));
    }

    private BigInteger decodeBigInt(String value) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(trimToEmpty(value))));
    }

    private String padBase64(String value) {
        int mod = value.length() % 4;
        return mod == 0 ? value : value + "====".substring(mod);
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to hash oidc value", ex);
        }
    }

    private String base64UrlNoPad(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
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

    private String normalizeIntent(String raw) {
        return INTENT_BIND_CURRENT_USER.equalsIgnoreCase(trimToEmpty(raw)) ? INTENT_BIND_CURRENT_USER : INTENT_LOGIN;
    }

    private String normalizeRedirectPath(String redirect) {
        String normalized = trimToEmpty(redirect);
        if (normalized.isBlank()
                || !normalized.startsWith("/")
                || normalized.startsWith("//")
                || normalized.contains("://")
                || normalized.contains("\n")
                || normalized.contains("\r")) {
            return DEFAULT_REDIRECT;
        }
        return normalized;
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public record StartResult(
            String authorizeUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie intentCookie,
            ResponseCookie verifierCookie,
            ResponseCookie nonceCookie,
            ResponseCookie bindUserCookie,
            ResponseCookie pendingBrowserCookie,
            ResponseCookie pendingSessionCookie
    ) {
    }

    public record CallbackResult(
            String redirectUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie verifierCookie,
            ResponseCookie nonceCookie,
            ResponseCookie intentCookie,
            ResponseCookie bindUserCookie,
            ResponseCookie pendingSessionCookie,
            ResponseCookie pendingBrowserCookie
    ) {
    }

    private record PendingSessionCreation(
            ResponseCookie sessionCookie,
            ResponseCookie browserCookie
    ) {
    }

    public record OidcTokenResponse(
            @JsonProperty("access_token") @JsonAlias({"accessToken"}) String accessToken,
            @JsonProperty("token_type") @JsonAlias({"tokenType"}) String tokenType,
            @JsonProperty("expires_in") @JsonAlias({"expiresIn"}) Long expiresIn,
            @JsonProperty("refresh_token") @JsonAlias({"refreshToken"}) String refreshToken,
            @JsonProperty("scope") String scope,
            @JsonProperty("id_token") @JsonAlias({"idToken"}) String idToken
    ) {
    }

    public record OidcIdTokenClaims(
            String subject,
            String issuer,
            String email,
            Boolean emailVerified,
            String preferredUsername,
            String name,
            String nonce,
            String azp
    ) {
    }

    public record OidcUserInfoClaims(
            String email,
            String username,
            String subject,
            Boolean emailVerified,
            String displayName,
            String avatarUrl
    ) {
    }

    public record OidcJwkSet(
            @JsonProperty("keys") List<OidcJwk> keys
    ) {
    }

    public record OidcJwk(
            @JsonProperty("kty") String kty,
            @JsonProperty("kid") String kid,
            @JsonProperty("use") String use,
            @JsonProperty("alg") String alg,
            @JsonProperty("n") String n,
            @JsonProperty("e") String e,
            @JsonProperty("crv") String crv,
            @JsonProperty("x") String x,
            @JsonProperty("y") String y
    ) {
    }

    private record JwkKey(
            String algorithm,
            Key key
    ) {
    }
}
