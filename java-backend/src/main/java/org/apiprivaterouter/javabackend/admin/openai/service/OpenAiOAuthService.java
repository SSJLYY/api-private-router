package org.apiprivaterouter.javabackend.admin.openai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.GenerateAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiExchangeCodeRequest;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiOAuthTokenResponse;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class OpenAiOAuthService {

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String DEFAULT_REDIRECT_URI = "http://localhost:1455/auth/callback";
    private static final String DEFAULT_SCOPES = "openid profile email offline_access";
    private static final String REFRESH_SCOPES = "openid profile email";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminProxyRepository proxyRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, OAuthSession> sessions = new ConcurrentHashMap<>();

    public OpenAiOAuthService(AdminProxyRepository proxyRepository, ObjectMapper objectMapper) {
        this.proxyRepository = proxyRepository;
        this.objectMapper = objectMapper;
    }

    public GenerateAuthUrlResponse generateAuthUrl(Long proxyId, String redirectUri) {
        cleanupExpiredSessions();
        String state = HexFormat.of().formatHex(randomBytes(32));
        String codeVerifier = HexFormat.of().formatHex(randomBytes(64));
        String codeChallenge = codeChallenge(codeVerifier);
        String sessionId = HexFormat.of().formatHex(randomBytes(16));
        String effectiveRedirectUri = blankToNull(redirectUri) == null ? DEFAULT_REDIRECT_URI : redirectUri.trim();
        ProxySettings proxy = resolveProxy(proxyId, null);
        sessions.put(sessionId, new OAuthSession(state, codeVerifier, CLIENT_ID, effectiveRedirectUri, proxy, Instant.now()));
        return new GenerateAuthUrlResponse(buildAuthorizationUrl(state, codeChallenge, effectiveRedirectUri), sessionId);
    }

    public OpenAiOAuthTokenResponse exchangeCode(OpenAiExchangeCodeRequest request) {
        OAuthSession session = getSessionOrThrow(request.session_id());
        if (!session.state().equals(request.state().trim())) {
            throw new IllegalArgumentException("invalid oauth state");
        }
        ProxySettings proxy = request.proxy_id() == null ? session.proxy() : resolveProxy(request.proxy_id(), session.proxy());
        String redirectUri = blankToNull(request.redirect_uri()) == null ? session.redirectUri() : request.redirect_uri().trim();
        TokenEndpointResponse token = exchangeCodeForToken(request.code().trim(), session.codeVerifier(), redirectUri, session.clientId(), proxy);
        sessions.remove(request.session_id());
        return enrichTokenResponse(token, session.clientId(), proxy);
    }

    public OpenAiOAuthTokenResponse refreshToken(String refreshToken, String clientId, Long proxyId) {
        String effectiveRefreshToken = blankToNull(refreshToken);
        if (effectiveRefreshToken == null) {
            throw new IllegalArgumentException("refresh_token is required");
        }
        String effectiveClientId = blankToNull(clientId) == null ? CLIENT_ID : clientId.trim();
        ProxySettings proxy = resolveProxy(proxyId, null);
        TokenEndpointResponse token = refreshTokenInternal(effectiveRefreshToken, effectiveClientId, proxy);
        return enrichTokenResponse(token, effectiveClientId, proxy);
    }

    public OpenAiOAuthTokenResponse refreshAccountToken(AdminAccountResponse account) {
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        if (!"openai".equals(account.platform()) || !"oauth".equals(account.type())) {
            throw new IllegalArgumentException("Only OpenAI OAuth accounts support token refresh");
        }
        String refreshToken = objectToString(account.credentials() == null ? null : account.credentials().get("refresh_token"));
        if (refreshToken == null) {
            throw new IllegalArgumentException("missing refresh_token");
        }
        String clientId = objectToString(account.credentials() == null ? null : account.credentials().get("client_id"));
        return refreshToken(refreshToken, clientId, account.proxy_id());
    }

    public Map<String, Object> buildAccountCredentials(OpenAiOAuthTokenResponse tokenInfo) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        putIfNotBlank(credentials, "access_token", tokenInfo.access_token());
        putIfNotBlank(credentials, "refresh_token", tokenInfo.refresh_token());
        putIfNotBlank(credentials, "id_token", tokenInfo.id_token());
        putIfNotBlank(credentials, "token_type", tokenInfo.token_type());
        if (tokenInfo.expires_at() > 0) {
            credentials.put("expires_at", Long.toString(tokenInfo.expires_at()));
        }
        putIfNotBlank(credentials, "scope", tokenInfo.scope());
        putIfNotBlank(credentials, "client_id", tokenInfo.client_id());
        putIfNotBlank(credentials, "email", tokenInfo.email());
        putIfNotBlank(credentials, "chatgpt_account_id", tokenInfo.chatgpt_account_id());
        putIfNotBlank(credentials, "chatgpt_user_id", tokenInfo.chatgpt_user_id());
        putIfNotBlank(credentials, "organization_id", tokenInfo.organization_id());
        putIfNotBlank(credentials, "plan_type", tokenInfo.plan_type());
        putIfNotBlank(credentials, "subscription_expires_at", tokenInfo.subscription_expires_at());
        return credentials;
    }

    public Map<String, Object> mergeExtra(Map<String, Object> currentExtra, OpenAiOAuthTokenResponse tokenInfo) {
        Map<String, Object> extra = new LinkedHashMap<>(currentExtra == null ? Map.of() : currentExtra);
        if (blankToNull(tokenInfo.privacy_mode()) != null) {
            extra.put("privacy_mode", tokenInfo.privacy_mode());
        }
        return extra;
    }

    private TokenEndpointResponse exchangeCodeForToken(
            String code,
            String codeVerifier,
            String redirectUri,
            String clientId,
            ProxySettings proxy
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("client_id", clientId);
        params.put("code", code);
        params.put("redirect_uri", redirectUri);
        params.put("code_verifier", codeVerifier);
        return postTokenForm(params, proxy, "token exchange failed");
    }

    private TokenEndpointResponse refreshTokenInternal(String refreshToken, String clientId, ProxySettings proxy) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", clientId);
        params.put("scope", REFRESH_SCOPES);
        return postTokenForm(params, proxy, "token refresh failed");
    }

    private TokenEndpointResponse postTokenForm(Map<String, String> params, ProxySettings proxy, String errorPrefix) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "codex-cli/0.91.0")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(params), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(errorPrefix + ": status " + response.statusCode() + ", body: " + response.body());
            }
            return objectMapper.readValue(response.body(), TokenEndpointResponse.class);
        } catch (IOException ex) {
            if (proxy == null) {
                throw new IllegalArgumentException("OpenAI OAuth request failed: no proxy is configured and this server could not reach OpenAI directly. Select a proxy that can access OpenAI, then retry; if the authorization code has expired, regenerate the authorization URL.");
            }
            throw new IllegalArgumentException("request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private OpenAiOAuthTokenResponse enrichTokenResponse(TokenEndpointResponse token, String clientId, ProxySettings proxy) {
        String organizationId = null;
        String email = null;
        String chatgptAccountId = null;
        String chatgptUserId = null;
        String planType = null;

        if (blankToNull(token.idToken()) != null) {
            IdTokenClaims claims = parseIdToken(token.idToken());
            if (claims != null) {
                email = claims.email();
                if (claims.openAiAuth() != null) {
                    chatgptAccountId = claims.openAiAuth().chatgptAccountId();
                    chatgptUserId = claims.openAiAuth().chatgptUserId();
                    planType = claims.openAiAuth().chatgptPlanType();
                    organizationId = defaultOrganizationId(claims.openAiAuth().organizations());
                }
            }
        }

        String orgFromAccessToken = extractOrganizationIdFromAccessToken(token.accessToken());
        if (organizationId == null) {
            organizationId = orgFromAccessToken;
        }

        ChatGptAccountInfo accountInfo = fetchChatGptAccountInfo(token.accessToken(), organizationId, proxy);
        if (accountInfo != null) {
            if (blankToNull(planType) == null) {
                planType = accountInfo.planType();
            }
            if (blankToNull(email) == null) {
                email = accountInfo.email();
            }
        }

        String privacyMode = disableOpenAiTraining(token.accessToken(), proxy);
        long expiresIn = token.expiresIn() == null ? 0L : token.expiresIn();

        return new OpenAiOAuthTokenResponse(
                token.accessToken(),
                token.refreshToken(),
                token.idToken(),
                token.tokenType(),
                expiresIn,
                Instant.now().getEpochSecond() + expiresIn,
                token.scope(),
                clientId,
                email,
                chatgptAccountId,
                chatgptUserId,
                organizationId,
                planType,
                accountInfo == null ? null : accountInfo.subscriptionExpiresAt(),
                privacyMode
        );
    }

    private ChatGptAccountInfo fetchChatGptAccountInfo(String accessToken, String organizationId, ProxySettings proxy) {
        if (blankToNull(accessToken) == null) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Origin", "https://chatgpt.com")
                .header("Referer", "https://chatgpt.com/")
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode accountsNode = root.path("accounts");
            if (!accountsNode.isObject()) {
                return null;
            }
            JsonNode matched = null;
            if (blankToNull(organizationId) != null) {
                matched = accountsNode.get(organizationId);
            }
            if (matched != null && matched.isObject()) {
                return buildAccountInfo(matched);
            }

            JsonNode defaultCandidate = null;
            JsonNode paidCandidate = null;
            Iterator<Map.Entry<String, JsonNode>> iterator = accountsNode.fields();
            while (iterator.hasNext()) {
                JsonNode node = iterator.next().getValue();
                if (!node.isObject()) {
                    continue;
                }
                String planType = extractPlanType(node);
                if (blankToNull(planType) == null) {
                    continue;
                }
                JsonNode accountNode = node.path("account");
                if (accountNode.path("is_default").asBoolean(false)) {
                    defaultCandidate = node;
                    break;
                }
                if (!"free".equalsIgnoreCase(planType) && paidCandidate == null) {
                    paidCandidate = node;
                }
                if (matched == null) {
                    matched = node;
                }
            }
            if (defaultCandidate != null) {
                return buildAccountInfo(defaultCandidate);
            }
            if (paidCandidate != null) {
                return buildAccountInfo(paidCandidate);
            }
            return matched == null ? null : buildAccountInfo(matched);
        } catch (Exception ex) {
            return null;
        }
    }

    private ChatGptAccountInfo buildAccountInfo(JsonNode node) {
        String email = blankToNull(node.path("account").path("email").asText(null));
        return new ChatGptAccountInfo(
                email,
                extractPlanType(node),
                blankToNull(node.path("entitlement").path("expires_at").asText(null))
        );
    }

    private String extractPlanType(JsonNode node) {
        String planType = blankToNull(node.path("account").path("plan_type").asText(null));
        if (planType != null) {
            return planType;
        }
        return blankToNull(node.path("entitlement").path("subscription_plan").asText(null));
    }

    private String disableOpenAiTraining(String accessToken, ProxySettings proxy) {
        if (blankToNull(accessToken) == null) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://chatgpt.com/backend-api/settings/account_user_setting?feature=training_allowed&value=false"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Origin", "https://chatgpt.com")
                .header("Referer", "https://chatgpt.com/")
                .header("Accept", "application/json")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .header("sec-fetch-dest", "empty")
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() == 403 || response.statusCode() == 503) {
                String lower = body.toLowerCase(Locale.ROOT);
                if (lower.contains("cloudflare") || lower.contains("cf-") || body.contains("Just a moment")) {
                    return "training_set_cf_blocked";
                }
            }
            return response.statusCode() >= 200 && response.statusCode() < 300 ? "training_off" : "training_set_failed";
        } catch (Exception ex) {
            return "training_set_failed";
        }
    }

    private IdTokenClaims parseIdToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            return objectMapper.readValue(decoded, IdTokenClaims.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractOrganizationIdFromAccessToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode root = objectMapper.readTree(decoded);
            JsonNode auth = root.get("https://api.openai.com/auth");
            if (auth == null || auth.isMissingNode()) {
                return null;
            }
            return blankToNull(auth.path("poid").asText(null));
        } catch (Exception ex) {
            return null;
        }
    }

    private String defaultOrganizationId(List<OrganizationClaim> organizations) {
        if (organizations == null || organizations.isEmpty()) {
            return null;
        }
        for (OrganizationClaim organization : organizations) {
            if (organization != null && organization.isDefault()) {
                return blankToNull(organization.id());
            }
        }
        return blankToNull(organizations.get(0).id());
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

    private String buildAuthorizationUrl(String state, String codeChallenge, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", CLIENT_ID);
        params.put("redirect_uri", redirectUri);
        params.put("scope", DEFAULT_SCOPES);
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("id_token_add_organizations", "true");
        params.put("codex_cli_simplified_flow", "true");
        return AUTHORIZE_URL + "?" + formEncode(params);
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

    private String codeChallenge(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256.digest(verifier.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
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
                .connectTimeout(Duration.ofSeconds(10))
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
                    return new PasswordAuthentication(proxy.username(), (proxy.password() == null ? "" : proxy.password()).toCharArray());
                }
            });
        }
        return builder.build();
    }

    private String padBase64(String value) {
        int mod = value.length() % 4;
        if (mod == 2) {
            return value + "==";
        }
        if (mod == 3) {
            return value + "=";
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String objectToString(Object value) {
        if (value == null) {
            return null;
        }
        return blankToNull(String.valueOf(value));
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (blankToNull(value) != null) {
            target.put(key, value.trim());
        }
    }

    private record OAuthSession(
            String state,
            String codeVerifier,
            String clientId,
            String redirectUri,
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

    private record ChatGptAccountInfo(
            String email,
            String planType,
            String subscriptionExpiresAt
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
    private record TokenEndpointResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            String scope
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdTokenClaims(
            String email,
            @JsonProperty("https://api.openai.com/auth") OpenAiAuthClaims openAiAuth
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiAuthClaims(
            @JsonProperty("chatgpt_account_id") String chatgptAccountId,
            @JsonProperty("chatgpt_user_id") String chatgptUserId,
            @JsonProperty("chatgpt_plan_type") String chatgptPlanType,
            List<OrganizationClaim> organizations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrganizationClaim(
            String id,
            @JsonProperty("is_default") boolean isDefault
    ) {
    }
}
