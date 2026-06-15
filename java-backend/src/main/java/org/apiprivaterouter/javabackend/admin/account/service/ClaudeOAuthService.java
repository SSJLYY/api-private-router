package org.apiprivaterouter.javabackend.admin.account.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ClaudeOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.account.model.CookieAuthRequest;
import org.apiprivaterouter.javabackend.admin.account.model.ExchangeAuthCodeRequest;
import org.apiprivaterouter.javabackend.admin.account.model.GenerateAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ClaudeOAuthService {

    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final String REDIRECT_URI = "https://platform.claude.com/oauth/code/callback";
    private static final String SCOPE_OAUTH = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
    private static final String SCOPE_API = "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
    private static final String SCOPE_INFERENCE = "user:inference";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminProxyRepository proxyRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, OAuthSession> sessions = new ConcurrentHashMap<>();

    public ClaudeOAuthService(AdminProxyRepository proxyRepository, ObjectMapper objectMapper) {
        this.proxyRepository = proxyRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void evictExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().createdAt().plus(SESSION_TTL).isBefore(now));
    }

    public GenerateAuthUrlResponse generateAuthUrl(Long proxyId) {
        return generateAuthUrlWithScope(SCOPE_OAUTH, proxyId);
    }

    public GenerateAuthUrlResponse generateSetupTokenUrl(Long proxyId) {
        return generateAuthUrlWithScope(SCOPE_INFERENCE, proxyId);
    }

    public ClaudeOAuthTokenResponse exchangeCode(ExchangeAuthCodeRequest request) {
        Objects.requireNonNull(request, "request is required");
        OAuthSession session = getSessionOrThrow(request.session_id());
        ProxySettings proxy = request.proxy_id() == null ? session.proxy() : resolveProxy(request.proxy_id(), session.proxy());
        ClaudeOAuthTokenResponse token = exchangeCodeForToken(
                request.code(),
                session.codeVerifier(),
                proxy,
                SCOPE_INFERENCE.equals(session.scope())
        );
        sessions.remove(request.session_id());
        return token;
    }

    public ClaudeOAuthTokenResponse refreshAccountToken(AdminAccountResponse account) {
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        if (!"oauth".equals(account.type()) || !"anthropic".equals(account.platform())) {
            throw new IllegalArgumentException("Only Anthropic OAuth accounts support token refresh");
        }
        String refreshToken = trimToNull(account.credentials() == null ? null : String.valueOf(account.credentials().get("refresh_token")));
        if (refreshToken == null) {
            throw new IllegalArgumentException("no refresh token available");
        }
        ProxySettings proxy = resolveProxy(account.proxy_id(), null);
        return refreshToken(refreshToken, proxy);
    }

    public Map<String, Object> buildAccountCredentials(ClaudeOAuthTokenResponse tokenInfo) {
        Map<String, Object> credentials = new java.util.LinkedHashMap<>();
        putIfNotBlank(credentials, "access_token", tokenInfo.access_token());
        putIfNotBlank(credentials, "token_type", tokenInfo.token_type());
        if (tokenInfo.expires_in() > 0) {
            credentials.put("expires_in", Long.toString(tokenInfo.expires_in()));
        }
        if (tokenInfo.expires_at() > 0) {
            credentials.put("expires_at", Long.toString(tokenInfo.expires_at()));
        }
        putIfNotBlank(credentials, "refresh_token", tokenInfo.refresh_token());
        putIfNotBlank(credentials, "scope", tokenInfo.scope());
        putIfNotBlank(credentials, "org_uuid", tokenInfo.org_uuid());
        putIfNotBlank(credentials, "account_uuid", tokenInfo.account_uuid());
        putIfNotBlank(credentials, "email_address", tokenInfo.email_address());
        return credentials;
    }

    public ClaudeOAuthTokenResponse cookieAuth(CookieAuthRequest request, boolean setupToken) {
        Objects.requireNonNull(request, "request is required");
        ProxySettings proxy = resolveProxy(request.proxy_id(), null);
        String sessionKey = trimToNull(request.code());
        if (sessionKey == null) {
            throw new IllegalArgumentException("code is required");
        }

        String scope = setupToken ? SCOPE_INFERENCE : SCOPE_API;
        String orgUuid = getOrganizationUuid(sessionKey, proxy);
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = generateState();
        String authCode = getAuthorizationCode(sessionKey, orgUuid, scope, codeChallenge, state, proxy);
        ClaudeOAuthTokenResponse token = exchangeCodeForToken(authCode, codeVerifier, proxy, setupToken);
        if (trimToNull(token.org_uuid()) == null && orgUuid != null) {
            return new ClaudeOAuthTokenResponse(
                    token.access_token(),
                    token.token_type(),
                    token.expires_in(),
                    token.expires_at(),
                    token.refresh_token(),
                    token.scope(),
                    orgUuid,
                    token.account_uuid(),
                    token.email_address()
            );
        }
        return token;
    }

    private GenerateAuthUrlResponse generateAuthUrlWithScope(String scope, Long proxyId) {
        cleanupExpiredSessions();
        String state = generateState();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String sessionId = generateSessionId();
        ProxySettings proxy = resolveProxy(proxyId, null);
        sessions.put(sessionId, new OAuthSession(state, codeVerifier, scope, proxy, Instant.now()));
        return new GenerateAuthUrlResponse(buildAuthorizationUrl(state, codeChallenge, scope), sessionId);
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

    private String getOrganizationUuid(String sessionKey, ProxySettings proxy) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://claude.ai/api/organizations"))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", "sessionKey=" + sessionKey)
                .GET()
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("failed to get organizations: status " + response.statusCode() + ", body: " + response.body());
            }
            ClaudeOrganization[] orgs = objectMapper.readValue(response.body(), ClaudeOrganization[].class);
            if (orgs == null || orgs.length == 0) {
                throw new IllegalArgumentException("no organizations found");
            }
            if (orgs.length == 1) {
                return trimToNull(orgs[0].uuid());
            }
            for (ClaudeOrganization org : orgs) {
                if ("team".equals(trimToNull(org.ravenType()))) {
                    return trimToNull(org.uuid());
                }
            }
            return trimToNull(orgs[0].uuid());
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private String getAuthorizationCode(
            String sessionKey,
            String orgUuid,
            String scope,
            String codeChallenge,
            String state,
            ProxySettings proxy
    ) {
        Map<String, Object> payload = Map.of(
                "response_type", "code",
                "client_id", CLIENT_ID,
                "organization_uuid", orgUuid,
                "redirect_uri", REDIRECT_URI,
                "scope", scope,
                "state", state,
                "code_challenge", codeChallenge,
                "code_challenge_method", "S256"
        );
        String body = writeJson(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://claude.ai/v1/oauth/" + orgUuid + "/authorize"))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", "sessionKey=" + sessionKey)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Origin", "https://claude.ai")
                .header("Referer", "https://claude.ai/new")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("failed to get authorization code: status " + response.statusCode() + ", body: " + response.body());
            }
            AuthorizeResponse authorizeResponse = objectMapper.readValue(response.body(), AuthorizeResponse.class);
            String redirectUri = trimToNull(authorizeResponse.redirectUri());
            if (redirectUri == null) {
                throw new IllegalArgumentException("no redirect_uri in response");
            }
            URI parsed = URI.create(redirectUri);
            String code = splitQuery(parsed.getRawQuery()).get("code");
            String responseState = splitQuery(parsed.getRawQuery()).get("state");
            if (trimToNull(code) == null) {
                throw new IllegalArgumentException("no authorization code in redirect_uri");
            }
            return trimToNull(responseState) == null ? code : code + "#" + responseState;
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private ClaudeOAuthTokenResponse exchangeCodeForToken(String code, String codeVerifier, ProxySettings proxy, boolean setupToken) {
        String authCode = code;
        String state = null;
        int separator = code == null ? -1 : code.indexOf('#');
        if (separator >= 0) {
            authCode = code.substring(0, separator);
            state = code.substring(separator + 1);
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("code", authCode);
        payload.put("grant_type", "authorization_code");
        payload.put("client_id", CLIENT_ID);
        payload.put("redirect_uri", REDIRECT_URI);
        payload.put("code_verifier", codeVerifier);
        if (trimToNull(state) != null) {
            payload.put("state", state);
        }
        if (setupToken) {
            payload.put("expires_in", 31536000);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("User-Agent", "axios/1.13.6")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("token exchange failed: status " + response.statusCode() + ", body: " + response.body());
            }
            TokenResponse token = objectMapper.readValue(response.body(), TokenResponse.class);
            long expiresIn = token.expiresIn() == null ? 0L : token.expiresIn();
            long expiresAt = Instant.now().getEpochSecond() + expiresIn;
            return new ClaudeOAuthTokenResponse(
                    trimToNull(token.accessToken()),
                    trimToNull(token.tokenType()),
                    expiresIn,
                    expiresAt,
                    trimToNull(token.refreshToken()),
                    trimToNull(token.scope()),
                    token.organization() == null ? null : trimToNull(token.organization().uuid()),
                    token.account() == null ? null : trimToNull(token.account().uuid()),
                    token.account() == null ? null : trimToNull(token.account().emailAddress())
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private ClaudeOAuthTokenResponse refreshToken(String refreshToken, ProxySettings proxy) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("grant_type", "refresh_token");
        payload.put("refresh_token", refreshToken);
        payload.put("client_id", CLIENT_ID);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("User-Agent", "axios/1.13.6")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("token refresh failed: status " + response.statusCode() + ", body: " + response.body());
            }
            TokenResponse token = objectMapper.readValue(response.body(), TokenResponse.class);
            long expiresIn = token.expiresIn() == null ? 0L : token.expiresIn();
            long expiresAt = Instant.now().getEpochSecond() + expiresIn;
            return new ClaudeOAuthTokenResponse(
                    trimToNull(token.accessToken()),
                    trimToNull(token.tokenType()),
                    expiresIn,
                    expiresAt,
                    trimToNull(token.refreshToken()),
                    trimToNull(token.scope()),
                    token.organization() == null ? null : trimToNull(token.organization().uuid()),
                    token.account() == null ? null : trimToNull(token.account().uuid()),
                    token.account() == null ? null : trimToNull(token.account().emailAddress())
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private HttpClient buildHttpClient(ProxySettings proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null && proxy.host() != null && !proxy.host().isBlank() && proxy.port() > 0) {
            builder.proxy(new FixedProxySelector(proxy.protocol(), proxy.host(), proxy.port()));
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
        }
        return builder.build();
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

    private String buildAuthorizationUrl(String state, String codeChallenge, String scope) {
        return AUTHORIZE_URL
                + "?code=true&client_id=" + urlEncode(CLIENT_ID)
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(REDIRECT_URI)
                + "&scope=" + urlEncode(scope)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + urlEncode(state);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String generateState() {
        return base64Url(randomBytes(32));
    }

    private String generateSessionId() {
        return HexFormat.of().formatHex(randomBytes(16));
    }

    private String generateCodeVerifier() {
        return base64Url(randomBytes(32));
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String generateCodeChallenge(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return base64Url(sha256.digest(verifier.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String base64Url(byte[] value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to encode request");
        }
    }

    private Map<String, String> splitQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().createdAt().plus(SESSION_TTL).isBefore(now));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (trimToNull(value) != null) {
            target.put(key, value.trim());
        }
    }

    private record OAuthSession(
            String state,
            String codeVerifier,
            String scope,
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

    private static final class FixedProxySelector extends ProxySelector {

        private final List<Proxy> proxies;

        private FixedProxySelector(String protocol, String host, int port) {
            Proxy.Type type = protocol != null && protocol.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
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
    private record ClaudeOrganization(
            String uuid,
            String name,
            @JsonProperty("raven_type") String ravenType
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AuthorizeResponse(
            @JsonProperty("redirect_uri") String redirectUri
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            String scope,
            TokenOrganization organization,
            TokenAccount account
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenOrganization(
            String uuid
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenAccount(
            String uuid,
            @JsonProperty("email_address") String emailAddress
    ) {
    }
}
