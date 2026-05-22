package org.apiprivaterouter.javabackend.admin.gemini.service;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiAuthUrlRequest;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiExchangeCodeRequest;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthCapabilitiesResponse;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.time.Duration;
import java.time.Instant;

@Service
public class GeminiOAuthGatewayService {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String BUILTIN_CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com";
    private static final String BUILTIN_CLIENT_SECRET_ENV = "GEMINI_CLI_OAUTH_CLIENT_SECRET";
    private static final String AI_STUDIO_REDIRECT_URI = "http://localhost:1455/auth/callback";
    private static final String GEMINI_CLI_REDIRECT_URI = "https://codeassist.google.com/authcode";
    private static final String DEFAULT_CODE_ASSIST_SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile";
    private static final String DEFAULT_AI_STUDIO_SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/generative-language.retriever";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long EXPIRES_AT_SAFETY_WINDOW = 300L;
    private static final long EXPIRES_AT_MIN_TTL = 30L;

    private final AdminProxyRepository proxyRepository;
    private final ConcurrentMap<String, OAuthSession> sessions = new ConcurrentHashMap<>();

    public GeminiOAuthGatewayService(AdminProxyRepository proxyRepository) {
        this.proxyRepository = proxyRepository;
    }

    public GeminiOAuthCapabilitiesResponse getCapabilities() {
        String clientId = trimToNull(System.getenv("GEMINI_OAUTH_CLIENT_ID"));
        String clientSecret = trimToNull(System.getenv("GEMINI_OAUTH_CLIENT_SECRET"));
        boolean enabled = clientId != null && clientSecret != null && !BUILTIN_CLIENT_ID.equals(clientId);
        return new GeminiOAuthCapabilitiesResponse(enabled, List.of(AI_STUDIO_REDIRECT_URI));
    }

    public GeminiAuthUrlResponse generateAuthUrl(
            GeminiAuthUrlRequest request,
            String origin,
            String forwardedProto,
            String forwardedHost,
            HttpServletRequest httpRequest
    ) {
        String oauthType = normalizeOauthType(request == null ? null : request.oauth_type());
        OAuthConfig config = effectiveConfig(oauthType);
        boolean builtinClient = BUILTIN_CLIENT_ID.equals(config.clientId());

        if ("ai_studio".equals(oauthType) && builtinClient) {
            throw new IllegalArgumentException("AI Studio OAuth requires a custom OAuth Client (GEMINI_OAUTH_CLIENT_ID / GEMINI_OAUTH_CLIENT_SECRET). If you don't want to configure an OAuth client, please use an AI Studio API Key account instead");
        }

        String state = base64Url(randomBytes(32));
        String codeVerifier = base64Url(randomBytes(32));
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String sessionId = hex(randomBytes(16));
        String redirectUri = builtinClient ? GEMINI_CLI_REDIRECT_URI : AI_STUDIO_REDIRECT_URI;

        String projectId = trimToNull(request == null ? null : request.project_id());
        ProxySettings proxy = resolveProxy(request == null ? null : request.proxy_id(), null);
        sessions.put(sessionId, new OAuthSession(
                state,
                codeVerifier,
                proxy,
                redirectUri,
                projectId,
                normalizeTierId(request == null ? null : request.tier_id()),
                oauthType,
                Instant.now()
        ));
        return new GeminiAuthUrlResponse(
                buildAuthorizationUrl(config, state, codeChallenge, redirectUri, projectId),
                sessionId,
                state
        );
    }

    public GeminiOAuthTokenResponse exchangeCode(GeminiExchangeCodeRequest request) {
        OAuthSession session = getSessionOrThrow(request.session_id());
        if (!session.state().equals(trimToNull(request.state()))) {
            throw new IllegalArgumentException("invalid state");
        }

        String oauthType = session.oauthType();
        if (trimToNull(request.oauth_type()) != null) {
            oauthType = normalizeOauthType(request.oauth_type());
        }

        ProxySettings proxy = request.proxy_id() == null
                ? session.proxy()
                : resolveProxy(request.proxy_id(), session.proxy());

        OAuthConfig config = effectiveConfig(oauthType);
        boolean builtinClient = BUILTIN_CLIENT_ID.equals(config.clientId());
        String redirectUri = builtinClient ? GEMINI_CLI_REDIRECT_URI : AI_STUDIO_REDIRECT_URI;
        if ("ai_studio".equals(oauthType) && builtinClient) {
            throw new IllegalArgumentException("AI Studio OAuth requires a custom OAuth Client. Please use an AI Studio API Key account, or configure GEMINI_OAUTH_CLIENT_ID / GEMINI_OAUTH_CLIENT_SECRET and re-authorize");
        }

        TokenResponse token = exchangeCodeForToken(
                trimToNull(request.code()),
                session.codeVerifier(),
                redirectUri,
                config,
                proxy
        );
        sessions.remove(request.session_id());

        String projectId = trimToNull(session.projectId());
        String tierId = normalizeTierId(trimToNull(request.tier_id()));
        if (tierId == null) {
            tierId = normalizeTierId(session.tierId());
        }

        if ("code_assist".equals(oauthType) && projectId == null) {
            throw new IllegalArgumentException("missing project_id");
        }

        if (tierId == null) {
            tierId = defaultTierId(oauthType);
        }

        long expiresAt = computeExpiresAt(token.expiresIn());
        return new GeminiOAuthTokenResponse(
                trimToNull(token.accessToken()),
                trimToNull(token.refreshToken()),
                token.expiresIn() == null ? 0L : token.expiresIn(),
                expiresAt,
                trimToNull(token.tokenType()),
                trimToNull(token.scope()),
                projectId,
                oauthType,
                tierId,
                Map.of()
        );
    }

    public GeminiOAuthTokenResponse refreshAccountToken(AdminAccountResponse account, AdminProxyResponse proxy) {
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        if (!"gemini".equals(account.platform()) || !"oauth".equals(account.type())) {
            throw new IllegalArgumentException("Only Gemini OAuth accounts support token refresh");
        }
        String refreshToken = objectToString(account.credentials() == null ? null : account.credentials().get("refresh_token"));
        if (refreshToken == null) {
            throw new IllegalArgumentException("no refresh token available");
        }
        String oauthType = normalizeOauthType(objectToString(account.credentials() == null ? null : account.credentials().get("oauth_type")));
        OAuthConfig config = effectiveConfig(oauthType);
        ProxySettings proxySettings = proxy == null
                ? resolveProxy(account.proxy_id(), null)
                : new ProxySettings(
                trimToNull(proxy.protocol()),
                trimToNull(proxy.host()),
                proxy.port(),
                trimToNull(proxy.username()),
                trimToNull(proxy.password())
        );

        TokenResponse token = refreshTokenInternal(refreshToken, config, proxySettings);
        String tierId = objectToString(account.credentials() == null ? null : account.credentials().get("tier_id"));
        if (tierId == null) {
            tierId = defaultTierId(oauthType);
        }
        String projectId = objectToString(account.credentials() == null ? null : account.credentials().get("project_id"));
        if ("code_assist".equals(oauthType) && projectId == null) {
            throw new IllegalArgumentException("failed to auto-detect project_id: empty result");
        }
        return new GeminiOAuthTokenResponse(
                trimToNull(token.accessToken()),
                trimToNull(token.refreshToken()),
                token.expiresIn() == null ? 0L : token.expiresIn(),
                computeExpiresAt(token.expiresIn()),
                trimToNull(token.tokenType()),
                trimToNull(token.scope()),
                projectId,
                oauthType,
                tierId,
                Map.of()
        );
    }

    public Map<String, Object> buildAccountCredentials(GeminiOAuthTokenResponse tokenInfo) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        putIfNotBlank(credentials, "access_token", tokenInfo.access_token());
        if (tokenInfo.expires_at() > 0) {
            credentials.put("expires_at", Long.toString(tokenInfo.expires_at()));
        }
        putIfNotBlank(credentials, "refresh_token", tokenInfo.refresh_token());
        putIfNotBlank(credentials, "token_type", tokenInfo.token_type());
        putIfNotBlank(credentials, "scope", tokenInfo.scope());
        putIfNotBlank(credentials, "project_id", tokenInfo.project_id());
        putIfNotBlank(credentials, "tier_id", tokenInfo.tier_id());
        putIfNotBlank(credentials, "oauth_type", tokenInfo.oauth_type());
        if (tokenInfo.extra() != null) {
            credentials.putAll(tokenInfo.extra());
        }
        return credentials;
    }

    public Map<String, Object> mergeExtra(Map<String, Object> currentExtra, GeminiOAuthTokenResponse tokenInfo) {
        return new LinkedHashMap<>(currentExtra == null ? Map.of() : currentExtra);
    }

    private OAuthConfig effectiveConfig(String oauthType) {
        String configuredClientId = trimToNull(System.getenv("GEMINI_OAUTH_CLIENT_ID"));
        String configuredClientSecret = trimToNull(System.getenv("GEMINI_OAUTH_CLIENT_SECRET"));
        String configuredScopes = normalizeScopes(System.getenv("GEMINI_OAUTH_SCOPES"));

        boolean forceBuiltin = "code_assist".equals(oauthType) || "google_one".equals(oauthType);
        String clientId = forceBuiltin ? null : configuredClientId;
        String clientSecret = forceBuiltin ? null : configuredClientSecret;

        if (clientId == null && clientSecret == null) {
            String builtinSecret = trimToNull(System.getenv(BUILTIN_CLIENT_SECRET_ENV));
            if (builtinSecret == null) {
                throw new IllegalArgumentException("built-in Gemini CLI OAuth client_secret is not configured; set GEMINI_CLI_OAUTH_CLIENT_SECRET or provide a custom OAuth client");
            }
            clientId = BUILTIN_CLIENT_ID;
            clientSecret = builtinSecret;
        } else if (clientId == null || clientSecret == null) {
            throw new IllegalArgumentException("OAuth client not configured: please set both client_id and client_secret (or leave both empty to use the built-in Gemini CLI client)");
        }

        boolean builtin = BUILTIN_CLIENT_ID.equals(clientId);
        String scopes = configuredScopes;
        if (scopes == null) {
            if ("ai_studio".equals(oauthType) && !builtin) {
                scopes = DEFAULT_AI_STUDIO_SCOPES;
            } else {
                scopes = DEFAULT_CODE_ASSIST_SCOPES;
            }
        } else if (builtin && ("ai_studio".equals(oauthType) || "google_one".equals(oauthType))) {
            scopes = stripRestrictedScopes(scopes);
        }

        if ("ai_studio".equals(oauthType)) {
            scopes = scopes.replace("https://www.googleapis.com/auth/generative-language", "https://www.googleapis.com/auth/generative-language.retriever");
        }
        return new OAuthConfig(clientId, clientSecret, scopes);
    }

    private String buildAuthorizationUrl(OAuthConfig config, String state, String codeChallenge, String redirectUri, String projectId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", config.clientId());
        params.put("redirect_uri", redirectUri);
        params.put("scope", config.scopes());
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("include_granted_scopes", "true");
        if (projectId != null) {
            params.put("project_id", projectId);
        }

        StringBuilder builder = new StringBuilder(AUTHORIZE_URL).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private String normalizeOauthType(String oauthType) {
        String normalized = trimToNull(oauthType);
        if (normalized == null) {
            return "code_assist";
        }
        normalized = normalized.toLowerCase();
        if (!List.of("code_assist", "google_one", "ai_studio").contains(normalized)) {
            throw new IllegalArgumentException("Invalid oauth_type: must be 'code_assist', 'google_one', or 'ai_studio'");
        }
        return normalized;
    }

    private String normalizeTierId(String tierId) {
        String normalized = trimToNull(tierId);
        return normalized == null ? null : normalized;
    }

    private String defaultTierId(String oauthType) {
        return switch (oauthType) {
            case "google_one" -> "google_one_free";
            case "ai_studio" -> "aistudio_free";
            default -> "gcp_standard";
        };
    }

    private String normalizeScopes(String scopes) {
        String normalized = trimToNull(scopes);
        if (normalized == null) {
            return null;
        }
        return String.join(" ", normalized.replace(',', ' ').trim().split("\\s+"));
    }

    private String stripRestrictedScopes(String scopes) {
        StringBuilder builder = new StringBuilder();
        for (String scope : scopes.split("\\s+")) {
            if (scope.startsWith("https://www.googleapis.com/auth/generative-language")
                    || scope.startsWith("https://www.googleapis.com/auth/drive")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(scope);
        }
        return builder.isEmpty() ? DEFAULT_CODE_ASSIST_SCOPES : builder.toString();
    }

    private String generateCodeChallenge(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return base64Url(sha256.digest(verifier.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private TokenResponse exchangeCodeForToken(
            String code,
            String codeVerifier,
            String redirectUri,
            OAuthConfig config,
            ProxySettings proxy
    ) {
        if (code == null) {
            throw new IllegalArgumentException("code is required");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());
        params.put("code", code);
        params.put("code_verifier", codeVerifier);
        params.put("redirect_uri", redirectUri);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("failed to exchange code: status " + response.statusCode() + ", body: " + response.body());
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), TokenResponse.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private TokenResponse refreshTokenInternal(String refreshToken, OAuthConfig config, ProxySettings proxy) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("token refresh failed: status " + response.statusCode() + ", body: " + response.body());
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), TokenResponse.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private OAuthSession getSessionOrThrow(String sessionId) {
        cleanupExpiredSessions();
        OAuthSession session = sessions.get(sessionId);
        if (session == null || session.createdAt().plus(SESSION_TTL).isBefore(Instant.now())) {
            sessions.remove(sessionId);
            throw new IllegalArgumentException("session not found or expired");
        }
        return session;
    }

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().createdAt().plus(SESSION_TTL).isBefore(now));
    }

    private ProxySettings resolveProxy(Long proxyId, ProxySettings fallback) {
        if (proxyId == null || proxyId <= 0) {
            return fallback;
        }
        return proxyRepository.getProxy(proxyId)
                .map(proxy -> new ProxySettings(
                        trimToNull(proxy.protocol()),
                        trimToNull(proxy.host()),
                        proxy.port(),
                        trimToNull(proxy.username()),
                        trimToNull(proxy.password())
                ))
                .orElse(fallback);
    }

    private HttpClient buildHttpClient(ProxySettings proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy == null || proxy.host() == null || proxy.host().isBlank() || proxy.port() <= 0) {
            return builder.build();
        }
        Proxy.Type type = proxy.protocol() != null && proxy.protocol().startsWith("socks")
                ? Proxy.Type.SOCKS
                : Proxy.Type.HTTP;
        builder.proxy(new FixedProxySelector(type, proxy.host(), proxy.port()));
        if (proxy.username() != null && !proxy.username().isBlank()) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            proxy.username(),
                            (proxy.password() == null ? "" : proxy.password()).toCharArray()
                    );
                }
            });
        }
        return builder.build();
    }

    private String formEncode(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private long computeExpiresAt(Long expiresIn) {
        long effectiveExpiresIn = expiresIn == null ? 0L : expiresIn;
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + effectiveExpiresIn - EXPIRES_AT_SAFETY_WINDOW;
        long minExpiresAt = now + EXPIRES_AT_MIN_TTL;
        return Math.max(expiresAt, minExpiresAt);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String objectToString(Object value) {
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (trimToNull(value) != null) {
            target.put(key, value.trim());
        }
    }

    private record OAuthConfig(
            String clientId,
            String clientSecret,
            String scopes
    ) {
    }

    private record OAuthSession(
            String state,
            String codeVerifier,
            ProxySettings proxy,
            String redirectUri,
            String projectId,
            String tierId,
            String oauthType,
            Instant createdAt
    ) {
    }

    private record ProxySettings(
            String protocol,
            String host,
            int port,
            String username,
            String password
    ) {
    }

    private static final class FixedProxySelector extends ProxySelector {

        private final List<Proxy> proxies;

        private FixedProxySelector(Proxy.Type type, String host, int port) {
            this.proxies = List.of(new Proxy(type, new InetSocketAddress(host, port)));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return proxies;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") Long expiresIn,
            @com.fasterxml.jackson.annotation.JsonProperty("token_type") String tokenType,
            String scope
    ) {
    }
}
