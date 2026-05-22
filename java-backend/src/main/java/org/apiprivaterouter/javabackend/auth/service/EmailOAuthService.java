package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthIdentityKey;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSessionView;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthTokenPair;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.PendingOAuthRepository;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class EmailOAuthService {

    private static final String COOKIE_PATH = "/api/v1/auth/oauth";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;
    private static final String COOKIE_STATE = "email_oauth_state";
    private static final String COOKIE_REDIRECT = "email_oauth_redirect";
    private static final String COOKIE_PROVIDER = "email_oauth_provider";
    private static final String COOKIE_AFFILIATE = "email_oauth_affiliate";
    private static final String DEFAULT_REDIRECT = "/dashboard";
    private static final String DEFAULT_FRONTEND_CALLBACK = "/auth/oauth/callback";

    private final ObjectMapper objectMapper;
    private final EmailOAuthConfigService configService;
    private final PendingOAuthRepository pendingOAuthRepository;
    private final PendingOAuthCookieService pendingOAuthCookieService;
    private final AuthLifecycleService authLifecycleService;
    private final AuthPublicEmailRepository authPublicEmailRepository;
    private final PublicSettingsService publicSettingsService;
    private final HttpClient httpClient;

    public EmailOAuthService(
            ObjectMapper objectMapper,
            EmailOAuthConfigService configService,
            PendingOAuthRepository pendingOAuthRepository,
            PendingOAuthCookieService pendingOAuthCookieService,
            AuthLifecycleService authLifecycleService,
            AuthPublicEmailRepository authPublicEmailRepository,
            PublicSettingsService publicSettingsService
    ) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.pendingOAuthRepository = pendingOAuthRepository;
        this.pendingOAuthCookieService = pendingOAuthCookieService;
        this.authLifecycleService = authLifecycleService;
        this.authPublicEmailRepository = authPublicEmailRepository;
        this.publicSettingsService = publicSettingsService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public StartResult buildStartResult(HttpServletRequest request, String provider, String redirect, String affiliateCode) {
        String normalizedProvider = normalizeProvider(provider);
        EmailOAuthConfigService.EmailOAuthProviderConfig config = configService.getRequiredConfig(normalizedProvider);
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirectTo = normalizeRedirectPath(redirect);
        boolean secure = pendingOAuthCookieService.isSecure(request);
        return new StartResult(
                buildAuthorizeUrl(config, state),
                responseCookie(COOKIE_STATE, encodeCookieValue(state), secure),
                responseCookie(COOKIE_REDIRECT, encodeCookieValue(redirectTo), secure),
                responseCookie(COOKIE_PROVIDER, encodeCookieValue(normalizedProvider), secure),
                affiliateCode == null || affiliateCode.trim().isEmpty()
                        ? clearCookie(COOKIE_AFFILIATE, secure)
                        : responseCookie(COOKIE_AFFILIATE, encodeCookieValue(affiliateCode.trim()), secure)
        );
    }

    public CallbackResult handleCallback(HttpServletRequest request, String provider, String code, String state, String error, String errorDescription) {
        String normalizedProvider = normalizeProvider(provider);
        EmailOAuthConfigService.EmailOAuthProviderConfig config = configService.getRequiredConfig(normalizedProvider);
        String frontendCallback = firstNonBlank(config.frontendRedirectUrl(), DEFAULT_FRONTEND_CALLBACK);
        boolean secure = pendingOAuthCookieService.isSecure(request);

        if (trimToNull(error) != null) {
            return errorRedirect(frontendCallback, trimToEmpty(error), firstNonBlank(errorDescription, error), secure);
        }
        if (trimToNull(code) == null || trimToNull(state) == null) {
            return errorRedirect(frontendCallback, "missing_params", "missing code/state", secure);
        }

        String expectedState = decodeCookieValue(readCookie(request, COOKIE_STATE));
        if (expectedState.isBlank() || !expectedState.equals(trimToEmpty(state))) {
            return errorRedirect(frontendCallback, "invalid_state", "invalid oauth state", secure);
        }
        String expectedProvider = decodeCookieValue(readCookie(request, COOKIE_PROVIDER));
        if (!normalizeProvider(expectedProvider).equals(normalizedProvider)) {
            return errorRedirect(frontendCallback, "invalid_state", "invalid oauth provider", secure);
        }

        String redirectTo = normalizeRedirectPath(decodeCookieValue(readCookie(request, COOKIE_REDIRECT)));
        if (redirectTo.isBlank()) {
            redirectTo = DEFAULT_REDIRECT;
        }

        EmailOAuthTokenResponse tokenResponse = exchangeCode(config, trimToEmpty(code));
        EmailOAuthProfile profile = fetchProfile(normalizedProvider, config, tokenResponse);

        PendingOAuthIdentityKey identityKey = new PendingOAuthIdentityKey(
                normalizedProvider,
                normalizedProvider,
                trimToEmpty(profile.subject())
        );
        PendingOAuthRepository.AuthIdentityRow identityOwner = pendingOAuthRepository.findAuthIdentityOwner(identityKey).orElse(null);
        AuthPublicEmailRepository.PublicAuthUserRow existingUser =
                authPublicEmailRepository.findUserByEmail(profile.email()).orElse(null);

        if (identityOwner != null && existingUser != null && identityOwner.userId() != existingUser.id()) {
            return errorRedirect(frontendCallback, "AUTH_IDENTITY_EMAIL_MISMATCH", "oauth identity belongs to a different email", secure);
        }

        if (identityOwner == null && existingUser == null) {
            PendingSessionCreation created = createPendingRegistrationSession(
                    request,
                    normalizedProvider,
                    frontendCallback,
                    redirectTo,
                    profile
            );
            return pendingRedirect(frontendCallback, created.sessionCookie(), created.browserCookie(), secure);
        }

        long userId = identityOwner != null ? identityOwner.userId() : existingUser.id();
        pendingOAuthRepository.ensureAuthIdentityForUser(userId, identityKey, null, profile.metadata());
        PendingOAuthTokenPair pair = authLifecycleService.issueTokenPairForUser(userId);
        return tokenRedirect(frontendCallback, redirectTo, pair, secure);
    }

    private PendingSessionCreation createPendingRegistrationSession(
            HttpServletRequest request,
            String provider,
            String frontendCallback,
            String redirectTo,
            EmailOAuthProfile profile
    ) {
        String browserSessionKey = UUID.randomUUID().toString().replace("-", "");
        String sessionToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String email = trimToEmpty(profile.email()).toLowerCase(Locale.ROOT);
        Map<String, Object> upstreamClaims = new LinkedHashMap<>();
        upstreamClaims.put("email", email);
        upstreamClaims.put("email_verified", profile.emailVerified());
        upstreamClaims.put("username", trimToEmpty(profile.username()));
        upstreamClaims.put("provider", provider);
        upstreamClaims.put("provider_key", provider);
        upstreamClaims.put("provider_subject", trimToEmpty(profile.subject()));
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
        if (profile.metadata() != null) {
            profile.metadata().forEach(upstreamClaims::putIfAbsent);
        }

        boolean invitationRequired = configInvitationRequired();
        Map<String, Object> completionResponse = new LinkedHashMap<>();
        completionResponse.put("step", "choose_account_action_required");
        completionResponse.put("error", invitationRequired ? "invitation_required" : "registration_completion_required");
        completionResponse.put("choice_reason", invitationRequired ? "invitation_required" : "registration_completion_required");
        completionResponse.put("adoption_required", false);
        completionResponse.put("create_account_allowed", true);
        completionResponse.put("existing_account_bindable", false);
        completionResponse.put("force_email_on_signup", true);
        completionResponse.put("invitation_required", invitationRequired);
        completionResponse.put("email", email);
        completionResponse.put("resolved_email", email);
        completionResponse.put("provider", provider);
        completionResponse.put("redirect", redirectTo);
        completionResponse.put("frontend_callback", frontendCallback);

        PendingOAuthSessionView session = pendingOAuthRepository.createPendingSession(new PendingOAuthRepository.CreatePendingSessionInput(
                sessionToken,
                "login",
                provider,
                provider,
                trimToEmpty(profile.subject()),
                null,
                redirectTo,
                email,
                "",
                upstreamClaims,
                Map.of("completion_response", completionResponse),
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

    private boolean configInvitationRequired() {
        return publicSettingsService.getPublicSettings().invitation_code_enabled();
    }

    private EmailOAuthTokenResponse exchangeCode(EmailOAuthConfigService.EmailOAuthProviderConfig config, String code) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(buildTokenForm(config, code)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            EmailOAuthTokenResponse body = objectMapper.readValue(response.body(), EmailOAuthTokenResponse.class);
            if (trimToNull(body.accessToken()) == null) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            return body;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
        }
    }

    private EmailOAuthProfile fetchProfile(
            String provider,
            EmailOAuthConfigService.EmailOAuthProviderConfig config,
            EmailOAuthTokenResponse token
    ) {
        String body = getJson(config.userInfoUrl(), token.accessToken());
        return switch (provider) {
            case "github" -> parseGitHubProfile(config, token.accessToken(), body);
            case "google" -> parseGoogleProfile(body);
            default -> throw new StructuredApiErrorException(400, "unsupported_provider", "unsupported oauth provider");
        };
    }

    private EmailOAuthProfile parseGitHubProfile(
            EmailOAuthConfigService.EmailOAuthProviderConfig config,
            String accessToken,
            String body
    ) {
        Map<String, Object> userInfo = readObjectMap(body);
        String subject = stringValue(userInfo, "id");
        if (subject.isBlank()) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
        }
        String email = resolveGitHubVerifiedEmail(config.emailsUrl(), accessToken);
        String login = stringValue(userInfo, "login");
        String name = stringValue(userInfo, "name");
        return new EmailOAuthProfile(
                subject,
                email,
                true,
                firstNonBlank(login, name, "github_" + subject),
                firstNonBlank(name, login),
                stringValue(userInfo, "avatar_url"),
                new LinkedHashMap<>(Map.of("login", login))
        );
    }

    private String resolveGitHubVerifiedEmail(String emailsUrl, String accessToken) {
        if (trimToNull(emailsUrl) == null) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
        }
        String body = getJson(emailsUrl, accessToken);
        try {
            List<?> items = objectMapper.readValue(body, List.class);
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> map = stringifyMap(raw);
                    if (Boolean.TRUE.equals(map.get("primary")) && Boolean.TRUE.equals(map.get("verified"))) {
                        String email = stringValue(map, "email");
                        if (!email.isBlank()) {
                            return email.trim().toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> map = stringifyMap(raw);
                    if (Boolean.TRUE.equals(map.get("verified"))) {
                        String email = stringValue(map, "email");
                        if (!email.isBlank()) {
                            return email.trim().toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
        }
        throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
    }

    private EmailOAuthProfile parseGoogleProfile(String body) {
        Map<String, Object> userInfo = readObjectMap(body);
        String subject = stringValue(userInfo, "sub");
        String email = stringValue(userInfo, "email").toLowerCase(Locale.ROOT);
        boolean verified = Boolean.TRUE.equals(userInfo.get("email_verified"));
        if (subject.isBlank() || email.isBlank() || !verified) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
        }
        String name = stringValue(userInfo, "name");
        return new EmailOAuthProfile(
                subject,
                email,
                true,
                firstNonBlank(stringValue(userInfo, "given_name"), name, email),
                name,
                stringValue(userInfo, "picture"),
                new LinkedHashMap<>(Map.of("email_verified", true))
        );
    }

    private String getJson(String url, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
            }
            return response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
        }
    }

    private String buildAuthorizeUrl(EmailOAuthConfigService.EmailOAuthProviderConfig config, String state) {
        return config.authorizeUrl()
                + "?response_type=code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&redirect_uri=" + urlEncode(config.redirectUrl())
                + "&state=" + urlEncode(state)
                + (config.scopes().isBlank() ? "" : "&scope=" + urlEncode(config.scopes()));
    }

    private String buildTokenForm(EmailOAuthConfigService.EmailOAuthProviderConfig config, String code) {
        return "grant_type=authorization_code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&client_secret=" + urlEncode(config.clientSecret())
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(config.redirectUrl());
    }

    private CallbackResult errorRedirect(String frontendCallback, String error, String description, boolean secure) {
        return new CallbackResult(
                redirectWithFragment(frontendCallback, Map.of(
                        "error", trimToEmpty(error),
                        "error_description", trimToEmpty(description)
                )),
                clearCookie(COOKIE_STATE, secure),
                clearCookie(COOKIE_REDIRECT, secure),
                clearCookie(COOKIE_PROVIDER, secure),
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
                clearCookie(COOKIE_PROVIDER, secure),
                clearCookie(COOKIE_AFFILIATE, secure),
                sessionCookie,
                browserCookie
        );
    }

    private CallbackResult tokenRedirect(String frontendCallback, String redirectTo, PendingOAuthTokenPair pair, boolean secure) {
        return new CallbackResult(
                redirectWithFragment(frontendCallback, Map.of(
                        "access_token", pair.accessToken(),
                        "refresh_token", pair.refreshToken(),
                        "expires_in", String.valueOf(pair.expiresIn()),
                        "token_type", pair.tokenType(),
                        "redirect", redirectTo
                )),
                clearCookie(COOKIE_STATE, secure),
                clearCookie(COOKIE_REDIRECT, secure),
                clearCookie(COOKIE_PROVIDER, secure),
                clearCookie(COOKIE_AFFILIATE, secure),
                null,
                null
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
            return new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    uri.getQuery(),
                    fragment.toString()
            ).toString();
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

    private String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("github".equals(normalized) || "google".equals(normalized)) {
            return normalized;
        }
        throw new StructuredApiErrorException(404, "OAUTH_PROVIDER_NOT_FOUND", "oauth provider not found");
    }

    private String normalizeRedirectPath(String redirect) {
        String value = trimToEmpty(redirect);
        if (value.isBlank() || !value.startsWith("/") || value.startsWith("//") || value.contains("://") || value.contains("\n") || value.contains("\r")) {
            return DEFAULT_REDIRECT;
        }
        return value;
    }

    private Map<String, Object> readObjectMap(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (IOException ex) {
            throw new StructuredApiErrorException(502, "userinfo_failed", "failed to fetch verified email");
        }
    }

    private Map<String, Object> stringifyMap(Map<?, ?> raw) {
        LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
        raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
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
            ResponseCookie providerCookie,
            ResponseCookie affiliateCookie
    ) {
    }

    public record CallbackResult(
            String redirectUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie providerCookie,
            ResponseCookie affiliateCookie,
            ResponseCookie pendingSessionCookie,
            ResponseCookie pendingBrowserCookie
    ) {
    }

    private record PendingSessionCreation(
            ResponseCookie sessionCookie,
            ResponseCookie browserCookie
    ) {
    }

    public record EmailOAuthTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("scope") String scope
    ) {
    }

    public record EmailOAuthProfile(
            String subject,
            String email,
            boolean emailVerified,
            String username,
            String displayName,
            String avatarUrl,
            Map<String, Object> metadata
    ) {
    }
}
