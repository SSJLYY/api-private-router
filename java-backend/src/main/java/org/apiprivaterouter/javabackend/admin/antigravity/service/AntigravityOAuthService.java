package org.apiprivaterouter.javabackend.admin.antigravity.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityExchangeCodeRequest;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityOAuthTokenResponse;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AntigravityOAuthService {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    private static final String CLIENT_SECRET_ENV = "ANTIGRAVITY_OAUTH_CLIENT_SECRET";
    private static final String REDIRECT_URI = "http://localhost:8085/callback";
    private static final String SCOPES = "https://www.googleapis.com/auth/cloud-platform "
            + "https://www.googleapis.com/auth/userinfo.email "
            + "https://www.googleapis.com/auth/userinfo.profile "
            + "https://www.googleapis.com/auth/cclog "
            + "https://www.googleapis.com/auth/experimentsandconfigs";
    private static final String PROD_BASE_URL = "https://cloudcode-pa.googleapis.com";
    private static final String DAILY_BASE_URL = "https://daily-cloudcode-pa.sandbox.googleapis.com";
    private static final String PRIVACY_BASE_URL = "https://daily-cloudcode-pa.googleapis.com";
    private static final String USER_AGENT = "antigravity/" + resolveUserAgentVersion() + " windows/amd64";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminProxyRepository proxyRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, OAuthSession> sessions = new ConcurrentHashMap<>();

    public AntigravityOAuthService(AdminProxyRepository proxyRepository, ObjectMapper objectMapper) {
        this.proxyRepository = proxyRepository;
        this.objectMapper = objectMapper;
    }

    public AntigravityAuthUrlResponse generateAuthUrl(Long proxyId) {
        cleanupExpiredSessions();
        String state = base64Url(randomBytes(32));
        String codeVerifier = base64Url(randomBytes(32));
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String sessionId = hex(randomBytes(16));
        ProxySettings proxy = resolveProxy(proxyId, null);
        sessions.put(sessionId, new OAuthSession(state, codeVerifier, proxy, Instant.now()));
        return new AntigravityAuthUrlResponse(buildAuthorizationUrl(state, codeChallenge), sessionId, state);
    }

    public AntigravityOAuthTokenResponse exchangeCode(AntigravityExchangeCodeRequest request) {
        OAuthSession session = getSessionOrThrow(request.session_id());
        if (!session.state().equals(request.state().trim())) {
            throw new IllegalArgumentException("invalid oauth state");
        }
        ProxySettings proxy = request.proxy_id() == null ? session.proxy() : resolveProxy(request.proxy_id(), session.proxy());
        TokenResponse token = exchangeCodeForToken(request.code().trim(), session.codeVerifier(), proxy);
        sessions.remove(request.session_id());
        return enrichToken(token, proxy);
    }

    public AntigravityOAuthTokenResponse refreshToken(String refreshToken, Long proxyId) {
        String effectiveRefreshToken = blankToNull(refreshToken);
        if (effectiveRefreshToken == null) {
            throw new IllegalArgumentException("refresh_token is required");
        }
        ProxySettings proxy = resolveProxy(proxyId, null);
        return enrichToken(refreshTokenInternal(effectiveRefreshToken, proxy), proxy);
    }

    public AntigravityOAuthTokenResponse refreshAccountToken(AdminAccountResponse account) {
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        if (!"antigravity".equals(account.platform()) || !"oauth".equals(account.type())) {
            throw new IllegalArgumentException("Only Antigravity OAuth accounts support token refresh");
        }
        String refreshToken = objectToString(account.credentials() == null ? null : account.credentials().get("refresh_token"));
        if (refreshToken == null) {
            throw new IllegalArgumentException("no refresh token available");
        }
        ProxySettings proxy = resolveProxy(account.proxy_id(), null);
        AntigravityOAuthTokenResponse tokenInfo = enrichToken(refreshTokenInternal(refreshToken, proxy), proxy);

        String email = tokenInfo.email() == null
                ? objectToString(account.credentials() == null ? null : account.credentials().get("email"))
                : tokenInfo.email();
        String projectId = tokenInfo.project_id() == null
                ? objectToString(account.credentials() == null ? null : account.credentials().get("project_id"))
                : tokenInfo.project_id();
        return new AntigravityOAuthTokenResponse(
                tokenInfo.access_token(),
                tokenInfo.refresh_token(),
                tokenInfo.expires_in(),
                tokenInfo.expires_at(),
                tokenInfo.token_type(),
                email,
                projectId,
                tokenInfo.plan_type(),
                tokenInfo.privacy_mode()
        );
    }

    public Map<String, Object> buildAccountCredentials(AntigravityOAuthTokenResponse tokenInfo) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        putIfNotBlank(credentials, "access_token", tokenInfo.access_token());
        if (tokenInfo.expires_at() > 0) {
            credentials.put("expires_at", Long.toString(tokenInfo.expires_at()));
        }
        putIfNotBlank(credentials, "refresh_token", tokenInfo.refresh_token());
        putIfNotBlank(credentials, "token_type", tokenInfo.token_type());
        putIfNotBlank(credentials, "email", tokenInfo.email());
        putIfNotBlank(credentials, "project_id", tokenInfo.project_id());
        putIfNotBlank(credentials, "plan_type", tokenInfo.plan_type());
        return credentials;
    }

    private AntigravityOAuthTokenResponse enrichToken(TokenResponse token, ProxySettings proxy) {
        String email = null;
        String projectId = null;
        String planType = null;

        try {
            email = blankToNull(getUserInfo(token.accessToken(), proxy).email());
        } catch (Exception ignored) {
        }

        try {
            ProjectInfo projectInfo = loadProjectInfoWithRetry(token.accessToken(), proxy, 3);
            if (projectInfo != null) {
                projectId = blankToNull(projectInfo.projectId());
                planType = blankToNull(projectInfo.planType());
            }
        } catch (Exception ignored) {
        }

        String privacyMode = setPrivacy(token.accessToken(), projectId, proxy);
        long expiresIn = token.expiresIn() == null ? 0L : token.expiresIn();
        long expiresAt = Instant.now().getEpochSecond() + expiresIn - 300;

        return new AntigravityOAuthTokenResponse(
                blankToNull(token.accessToken()),
                blankToNull(token.refreshToken()),
                expiresIn,
                expiresAt,
                blankToNull(token.tokenType()),
                email,
                projectId,
                planType,
                privacyMode
        );
    }

    private TokenResponse exchangeCodeForToken(String code, String codeVerifier, ProxySettings proxy) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", CLIENT_ID);
        params.put("client_secret", resolveClientSecret());
        params.put("code", code);
        params.put("redirect_uri", REDIRECT_URI);
        params.put("grant_type", "authorization_code");
        params.put("code_verifier", codeVerifier);
        return postTokenForm(params, proxy, "token exchange failed");
    }

    private TokenResponse refreshTokenInternal(String refreshToken, ProxySettings proxy) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", CLIENT_ID);
        params.put("client_secret", resolveClientSecret());
        params.put("refresh_token", refreshToken);
        params.put("grant_type", "refresh_token");
        return postTokenForm(params, proxy, "token refresh failed");
    }

    private TokenResponse postTokenForm(Map<String, String> params, ProxySettings proxy, String errorPrefix) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(errorPrefix + ": status " + response.statusCode() + ", body: " + response.body());
            }
            return objectMapper.readValue(response.body(), TokenResponse.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private UserInfoResponse getUserInfo(String accessToken, ProxySettings proxy) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USER_INFO_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("userinfo failed: status " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), UserInfoResponse.class);
    }

    private ProjectInfo loadProjectInfoWithRetry(String accessToken, ProxySettings proxy, int maxRetries) throws IOException, InterruptedException {
        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleepBackoff(attempt, 8);
            }
            try {
                ProjectInfo projectInfo = loadProjectInfo(accessToken, proxy);
                if (projectInfo != null && blankToNull(projectInfo.projectId()) != null) {
                    return projectInfo;
                }
                if (projectInfo != null && blankToNull(projectInfo.defaultTierId()) != null) {
                    String projectId = onboardUser(accessToken, projectInfo.defaultTierId(), proxy);
                    if (blankToNull(projectId) != null) {
                        return new ProjectInfo(projectId, projectInfo.planType(), projectInfo.defaultTierId());
                    }
                }
                lastError = new IOException("missing project_id");
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        if (lastError instanceof IOException io) {
            throw io;
        }
        if (lastError instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new IOException(lastError == null ? "missing project_id" : lastError.getMessage(), lastError);
    }

    private ProjectInfo loadProjectInfo(String accessToken, ProxySettings proxy) throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of(
                "metadata", Map.of(
                        "ideType", "ANTIGRAVITY",
                        "ideVersion", "1.20.6",
                        "ideName", "antigravity"
                )
        );
        Exception lastError = null;
        for (String baseUrl : List.of(PROD_BASE_URL, DAILY_BASE_URL)) {
            try {
                JsonNode root = postJson(baseUrl + "/v1internal:loadCodeAssist", accessToken, payload, proxy);
                return new ProjectInfo(
                        blankToNull(root.path("cloudaicompanionProject").asText(null)),
                        extractPlanType(root),
                        resolveDefaultTierId(root)
                );
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        if (lastError instanceof IOException io) {
            throw io;
        }
        if (lastError instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new IOException(lastError == null ? "loadCodeAssist failed" : lastError.getMessage(), lastError);
    }

    private String onboardUser(String accessToken, String tierId, ProxySettings proxy) throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of(
                "tierId", tierId,
                "metadata", Map.of(
                        "ideType", "ANTIGRAVITY",
                        "platform", "PLATFORM_UNSPECIFIED",
                        "pluginType", "GEMINI"
                )
        );
        Exception lastError = null;
        for (String baseUrl : List.of(PROD_BASE_URL, DAILY_BASE_URL)) {
            try {
                for (int attempt = 0; attempt < 5; attempt++) {
                    JsonNode root = postJson(baseUrl + "/v1internal:onboardUser", accessToken, payload, proxy);
                    if (root.path("done").asBoolean(false)) {
                        JsonNode response = root.path("response");
                        String projectId = blankToNull(response.path("cloudaicompanionProject").path("id").asText(null));
                        if (projectId == null) {
                            projectId = blankToNull(response.path("cloudaicompanionProject").asText(null));
                        }
                        if (projectId != null) {
                            return projectId;
                        }
                        throw new IOException("onboardUser finished without project_id");
                    }
                    sleepSeconds(2);
                }
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        if (lastError instanceof IOException io) {
            throw io;
        }
        if (lastError instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        throw new IOException(lastError == null ? "onboardUser failed" : lastError.getMessage(), lastError);
    }

    private String setPrivacy(String accessToken, String projectId, ProxySettings proxy) {
        if (blankToNull(accessToken) == null || blankToNull(projectId) == null) {
            return "privacy_set_failed";
        }
        try {
            JsonNode setRoot = postJson(PRIVACY_BASE_URL + "/v1internal:setUserSettings", accessToken, Map.of("user_settings", Map.of()), proxy);
            JsonNode setUserSettings = setRoot.path("userSettings");
            if (setUserSettings.isObject() && setUserSettings.has("telemetryEnabled")) {
                return "privacy_set_failed";
            }
            JsonNode infoRoot = postJson(PRIVACY_BASE_URL + "/v1internal:fetchUserInfo", accessToken, Map.of("project", projectId), proxy);
            JsonNode userSettings = infoRoot.path("userSettings");
            return userSettings.isMissingNode() || !userSettings.has("telemetryEnabled") ? "privacy_set" : "privacy_set_failed";
        } catch (Exception ex) {
            return "privacy_set_failed";
        }
    }

    private JsonNode postJson(String url, String accessToken, Object payload, ProxySettings proxy) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("User-Agent", USER_AGENT)
                .header("X-Goog-Api-Client", "gl-node/22.21.1")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("request failed: status " + response.statusCode() + ", body: " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private String extractPlanType(JsonNode root) {
        JsonNode paidTier = root.path("paidTier");
        String tierId = blankToNull(paidTier.path("id").asText(null));
        if (tierId == null && paidTier.isTextual()) {
            tierId = blankToNull(paidTier.asText());
        }
        if (tierId == null) {
            JsonNode currentTier = root.path("currentTier");
            tierId = blankToNull(currentTier.path("id").asText(null));
            if (tierId == null && currentTier.isTextual()) {
                tierId = blankToNull(currentTier.asText());
            }
        }
        if (tierId == null) {
            return null;
        }
        return switch (tierId.toLowerCase(Locale.ROOT)) {
            case "free-tier" -> "Free";
            case "g1-pro-tier" -> "Pro";
            case "g1-ultra-tier" -> "Ultra";
            default -> tierId;
        };
    }

    private String resolveDefaultTierId(JsonNode root) {
        JsonNode allowedTiers = root.path("allowedTiers");
        if (!allowedTiers.isArray()) {
            return null;
        }
        for (JsonNode allowedTier : allowedTiers) {
            if (allowedTier.path("isDefault").asBoolean(false)) {
                return blankToNull(allowedTier.path("id").asText(null));
            }
        }
        return null;
    }

    private ProxySettings resolveProxy(Long proxyId, ProxySettings fallback) {
        if (proxyId == null || proxyId <= 0) {
            return fallback;
        }
        return proxyRepository.getProxy(proxyId)
                .map(proxy -> new ProxySettings(
                        blankToNull(proxy.protocol()),
                        blankToNull(proxy.host()),
                        proxy.port(),
                        blankToNull(proxy.username()),
                        blankToNull(proxy.password())
                ))
                .orElse(fallback);
    }

    private HttpClient buildHttpClient(ProxySettings proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy == null || blankToNull(proxy.host()) == null || proxy.port() <= 0) {
            return builder.build();
        }
        Proxy.Type type = proxy.protocol() != null && proxy.protocol().startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        builder.proxy(new FixedProxySelector(type, proxy.host(), proxy.port()));
        if (blankToNull(proxy.username()) != null) {
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

    private String buildAuthorizationUrl(String state, String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", CLIENT_ID);
        params.put("redirect_uri", REDIRECT_URI);
        params.put("response_type", "code");
        params.put("scope", SCOPES);
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("include_granted_scopes", "true");
        return AUTHORIZE_URL + "?" + formEncode(params);
    }

    private String formEncode(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
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

    private void sleepBackoff(int attempt, int maxSeconds) throws InterruptedException {
        int seconds = Math.min(1 << Math.max(attempt - 1, 0), maxSeconds);
        sleepSeconds(seconds);
    }

    private void sleepSeconds(int seconds) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(seconds).toMillis());
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String objectToString(Object value) {
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (blankToNull(value) != null) {
            target.put(key, value.trim());
        }
    }

    private static String resolveClientSecret() {
        String env = System.getenv(CLIENT_SECRET_ENV);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        throw new IllegalStateException(CLIENT_SECRET_ENV + " is required");
    }

    private static String resolveUserAgentVersion() {
        String env = System.getenv("ANTIGRAVITY_USER_AGENT_VERSION");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "1.21.9";
    }

    private record OAuthSession(
            String state,
            String codeVerifier,
            ProxySettings proxy,
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

    private record ProjectInfo(
            String projectId,
            String planType,
            String defaultTierId
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("refresh_token") String refreshToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserInfoResponse(
            String email
    ) {
    }
}
