package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.model.ClaudeCountTokensResponse;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GatewayCountTokensService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final AdminAccountRepository accountRepository;
    private final AdminProxyRepository proxyRepository;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public GatewayCountTokensService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
    }

    public ClaudeCountTokensResponse countTokens(GatewayRuntimeContext runtimeContext, byte[] requestBody, String requestPath) {
        GatewayAccountSummary accountSummary = runtimeContext.account();
        if (accountSummary == null) {
            throw new AnthropicApiErrorException(503, "api_error", "No available accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(accountSummary.id())
                .orElseThrow(() -> new HttpStatusException(503, "No available accounts"));
        if (!supportsAnthropic(account.platform())) {
            throw new AnthropicApiErrorException(404, "not_found_error", "Token counting is not supported for this platform");
        }
        if ("anthropic".equalsIgnoreCase(account.platform()) && "bedrock".equalsIgnoreCase(account.type())) {
            throw new AnthropicApiErrorException(404, "not_found_error", "count_tokens endpoint is not supported for Bedrock");
        }

        Map<String, Object> payload = readPayload(requestBody);
        String requestedModel = stringValue(payload.get("model"));
        if (requestedModel == null || requestedModel.isBlank()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
        }
        payload.put("model", resolveMappedModel(account, requestedModel));
        byte[] encoded = writePayload(payload);

        HttpRequest request = buildRequest(account, encoded, requestPath);
        HttpResponse<String> response = send(account, request);
        if (response.statusCode() >= 400) {
            throw translateError(response);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            return new ClaudeCountTokensResponse(root.path("input_tokens").asLong(0));
        } catch (IOException ex) {
            throw new HttpStatusException(502, "invalid upstream response");
        }
    }

    private HttpRequest buildRequest(AdminAccountResponse account, byte[] body, String requestPath) {
        String url = resolveCountTokensUrl(account);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        String apiKey = stringValue(account.credentials(), "api_key");
        String accessToken = stringValue(account.credentials(), "access_token");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("x-api-key", apiKey);
        } else if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
            builder.header("anthropic-beta", "token-counting-2024-11-01");
        } else {
            throw new HttpStatusException(503, "No upstream credentials available");
        }

        if (requestPath != null && requestPath.contains("/antigravity/")) {
            builder.header("x-api-private-router-java-gateway", "antigravity");
        }
        return builder.build();
    }

    private HttpResponse<String> send(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new HttpStatusException(502, "upstream request interrupted");
        } catch (IOException ex) {
            throw new HttpStatusException(502, "upstream request failed");
        }
    }

    private HttpClient buildHttpClient(AdminAccountResponse account) {
        AdminProxyResponse proxy = account.proxy_id() == null || account.proxy_id() <= 0
                ? null
                : proxyRepository.getProxy(account.proxy_id()).orElse(null);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy == null || proxy.host() == null || proxy.host().isBlank() || proxy.port() <= 0) {
            return builder.build();
        }
        Proxy.Type type = proxy.protocol() != null && proxy.protocol().toLowerCase(Locale.ROOT).startsWith("socks")
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

    private String resolveCountTokensUrl(AdminAccountResponse account) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        baseUrl = upstreamUrlGuard.normalizeAccountBaseUrl(account.platform(), account.type(), baseUrl, "https://api.anthropic.com");
        String normalized = trimTrailingSlash(baseUrl);
        if ("antigravity".equalsIgnoreCase(account.platform()) && "apikey".equalsIgnoreCase(account.type())) {
            normalized = normalized + "/antigravity";
        }
        return normalized + "/v1/messages/count_tokens?beta=true";
    }

    private Map<String, Object> readPayload(byte[] body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private byte[] writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to encode request");
        }
    }

    private String resolveMappedModel(AdminAccountResponse account, String requestedModel) {
        Map<String, String> mapping = extractStringMap(account.credentials() == null ? null : account.credentials().get("model_mapping"));
        if (mapping.isEmpty()) {
            return requestedModel;
        }
        String exact = mapping.get(requestedModel);
        if (exact != null && !exact.isBlank()) {
            return exact.trim();
        }
        String bestMatch = null;
        int bestScore = -1;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String pattern = entry.getKey();
            String target = entry.getValue();
            if (pattern == null || target == null || !pattern.contains("*")) {
                continue;
            }
            String regex = java.util.regex.Pattern.quote(pattern).replace("\\*", ".*");
            if (!requestedModel.matches(regex)) {
                continue;
            }
            int score = pattern.replace("*", "").length();
            if (score > bestScore) {
                bestScore = score;
                bestMatch = target.trim();
            }
        }
        return bestMatch == null || bestMatch.isBlank() ? requestedModel : bestMatch;
    }

    private AnthropicApiErrorException translateError(HttpResponse<String> response) {
        String message = extractErrorMessage(response.body());
        if (message == null || message.isBlank()) {
            message = "Upstream request failed";
        }
        int status = response.statusCode();
        if (status == 404 && message.toLowerCase(Locale.ROOT).contains("count_tokens")) {
            return new AnthropicApiErrorException(404, "not_found_error", "Token counting is not supported for this platform");
        }
        String errorType = switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "api_error";
        };
        return new AnthropicApiErrorException(status >= 400 ? status : 502, errorType, message);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            List<String> candidates = List.of(
                    root.path("error").path("message").asText(),
                    root.path("message").asText(),
                    root.path("detail").asText(),
                    root.path("error").asText()
            );
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            return body;
        }
        return body;
    }

    private boolean supportsAnthropic(String platform) {
        String normalized = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        return "anthropic".equals(normalized) || "antigravity".equals(normalized) || normalized.isBlank();
    }

    private Map<String, String> extractStringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString().trim();
            String mapped = entry.getValue().toString().trim();
            if (!key.isEmpty() && !mapped.isEmpty()) {
                result.put(key, mapped);
            }
        }
        return result;
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
        public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
        }
    }
}
