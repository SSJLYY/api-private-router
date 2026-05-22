package org.apiprivaterouter.javabackend.gateway.service;

import io.jsonwebtoken.Jwts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class GatewayAnthropicMessagesService {

    private static final String CLAUDE_DEFAULT_BETA_HEADER = "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14";
    private static final String CLAUDE_API_KEY_BETA_HEADER = "claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14";
    private static final String VERTEX_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String VERTEX_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String VERTEX_DEFAULT_LOCATION = "us-central1";
    private static final String VERTEX_ANTHROPIC_VERSION = "vertex-2023-10-16";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern VERTEX_LOCATION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern VERTEX_ANTHROPIC_DATED_MODEL_ID_PATTERN = Pattern.compile("^(.+)-([0-9]{8})$");
    private static final Pattern VERTEX_ANTHROPIC_ALREADY_DATED_ID_PATTERN = Pattern.compile("^.+@[0-9]{8}$");
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept",
            "accept-language",
            "anthropic-beta",
            "anthropic-version",
            "content-type",
            "user-agent"
    );
    private static final Set<String> RESPONSE_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "content-length"
    );

    private final AdminAccountRepository accountRepository;
    private final AdminProxyRepository proxyRepository;
    private final GatewayAnthropicBedrockService bedrockService;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public GatewayAnthropicMessagesService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            GatewayAnthropicBedrockService bedrockService,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.bedrockService = bedrockService;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
    }

    public boolean canHandle(GatewayRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            return false;
        }
        String platform = normalize(runtimeContext.account().platform());
        String type = normalize(runtimeContext.account().type());
        if ("anthropic".equals(platform) && "service_account".equals(type)) {
            return true;
        }
        if ("anthropic".equals(platform) && "bedrock".equals(type)) {
            return true;
        }
        return isOAuthLikeType(type) || "apikey".equals(type)
                ? ("anthropic".equals(platform) || "antigravity".equals(platform))
                : false;
    }

    public void forward(GatewayRuntimeContext runtimeContext, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        if (runtimeContext.account() == null) {
            throw new AnthropicApiErrorException(503, "api_error", "No available accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new AnthropicApiErrorException(503, "api_error", "No available accounts"));
        if (bedrockService.canHandle(account)) {
            GatewayAnthropicBedrockService.PreparedBedrockRequest prepared = bedrockService.prepareMessagesRequest(
                    account,
                    body,
                    request == null ? null : request.getHeader("anthropic-beta")
            );
            HttpRequest upstreamRequest = bedrockService.buildRequest(account, prepared);
            HttpResponse<InputStream> upstream = bedrockService.send(account, upstreamRequest);
            bedrockService.writeMessagesResponse(response, upstream, prepared.stream());
            return;
        }
        if (!supportsPlatform(account.platform())) {
            throw new AnthropicApiErrorException(503, "api_error", "No available compatible accounts");
        }
        if (!"apikey".equalsIgnoreCase(account.type())
                && !isOAuthLikeType(account.type())
                && !isServiceAccountType(account.type())) {
            throw new AnthropicApiErrorException(501, "unsupported_error", "Messages forwarding is not supported for this account type yet");
        }
        if (isServiceAccountType(account.type()) && !"anthropic".equalsIgnoreCase(account.platform())) {
            throw new AnthropicApiErrorException(501, "unsupported_error", "Anthropic service account forwarding is only available for anthropic accounts");
        }

        MessagePayload payload = preparePayload(body, account);
        HttpRequest upstreamRequest = buildRequest(account, request, payload);
        HttpResponse<InputStream> upstream = send(account, upstreamRequest);
        writeResponse(response, upstream, payload.stream());
    }

    private MessagePayload preparePayload(byte[] body, AdminAccountResponse account) {
        if (body == null || body.length == 0) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            JsonNode modelNode = objectNode.get("model");
            if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
            }
            String requestedModel = modelNode.asText().trim();
            String mappedModel = resolveMappedModel(account, requestedModel);
            if (isServiceAccountType(account.type())) {
                mappedModel = normalizeVertexAnthropicModel(normalizeClaudeModel(mappedModel));
            }
            if (!mappedModel.equals(requestedModel)) {
                objectNode.put("model", mappedModel);
            }
            return new MessagePayload(
                    objectMapper.writeValueAsBytes(objectNode),
                    objectNode.path("stream").asBoolean(false),
                    mappedModel
            );
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private HttpRequest buildRequest(AdminAccountResponse account, HttpServletRequest inbound, MessagePayload payload) {
        if (isServiceAccountType(account.type())) {
            return buildServiceAccountRequest(account, inbound, payload);
        }
        String authToken = resolveAuthToken(account);
        if (authToken == null) {
            throw new AnthropicApiErrorException(503, "api_error", "No upstream credentials available");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(buildMessagesUrl(account)))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.body()))
                .header("Content-Type", "application/json");

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Content-Type", "application/json");
        if (inbound.getHeader("anthropic-version") == null || inbound.getHeader("anthropic-version").isBlank()) {
            builder.setHeader("anthropic-version", "2023-06-01");
        }
        if (isOAuthLikeType(account.type())) {
            builder.setHeader("Authorization", "Bearer " + authToken);
            builder.setHeader("anthropic-beta", resolveAnthropicBetaHeader(inbound, CLAUDE_DEFAULT_BETA_HEADER));
        } else {
            builder.setHeader("x-api-key", authToken);
            builder.setHeader("anthropic-beta", resolveAnthropicBetaHeader(inbound, CLAUDE_API_KEY_BETA_HEADER));
            if ("antigravity".equalsIgnoreCase(account.platform())) {
                builder.setHeader("Authorization", "Bearer " + authToken);
            }
        }
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
        }
        return builder.build();
    }

    private HttpRequest buildServiceAccountRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            MessagePayload payload
    ) {
        String accessToken = exchangeVertexServiceAccountAccessToken(account);
        String projectId = resolveVertexProjectId(account);
        String location = resolveVertexLocation(account, payload.model());
        String url = buildVertexAnthropicUrl(projectId, location, payload.model(), payload.stream());
        byte[] vertexBody = buildVertexAnthropicRequestBody(payload.body());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(vertexBody))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken);

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower) || "anthropic-version".equals(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Authorization", "Bearer " + accessToken);
        builder.setHeader("anthropic-beta", resolveAnthropicBetaHeader(inbound, CLAUDE_DEFAULT_BETA_HEADER));
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
        }
        return builder.build();
    }

    private String resolveAuthToken(AdminAccountResponse account) {
        if (account == null) {
            return null;
        }
        if (isOAuthLikeType(account.type())) {
            return stringValue(account.credentials(), "access_token");
        }
        return stringValue(account.credentials(), "api_key");
    }

    private String resolveAnthropicBetaHeader(HttpServletRequest inbound, String fallback) {
        String direct = inbound == null ? null : inbound.getHeader("anthropic-beta");
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        return fallback;
    }

    private HttpResponse<InputStream> send(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnthropicApiErrorException(502, "upstream_error", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "upstream_error", "Upstream request failed");
        }
    }

    private void writeResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, boolean requestedStream) {
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            if (name == null || RESPONSE_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        if (response.getContentType() == null) {
            response.setContentType(upstream.headers().firstValue("content-type")
                    .orElse(requestedStream ? "text/event-stream" : "application/json"));
        }
        if (requestedStream) {
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
        }
        try (InputStream input = upstream.body()) {
            ServletOutputStream output = response.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (requestedStream) {
                    output.flush();
                }
            }
            response.flushBuffer();
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to write upstream response");
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

    private String buildMessagesUrl(AdminAccountResponse account) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        baseUrl = upstreamUrlGuard.normalizeAccountBaseUrl(account.platform(), account.type(), baseUrl, "https://api.anthropic.com");
        String normalized = trimTrailingSlash(baseUrl);
        if (normalized.endsWith("/v1/messages") || normalized.endsWith("/v1/messages?beta=true")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/messages?beta=true";
        }
        if ("antigravity".equalsIgnoreCase(account.platform())
                && "apikey".equalsIgnoreCase(account.type())
                && !normalized.endsWith("/antigravity")) {
            normalized = normalized + "/antigravity";
        }
        return normalized + "/v1/messages?beta=true";
    }

    private String exchangeVertexServiceAccountAccessToken(AdminAccountResponse account) {
        VertexServiceAccountKey key = parseVertexServiceAccountKey(account);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(1));
        String assertion;
        try {
            var jwtBuilder = Jwts.builder()
                    .issuer(key.clientEmail())
                    .claim("scope", VERTEX_SCOPE)
                    .audience().add(VERTEX_TOKEN_URL).and()
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(expiresAt));
            if (key.privateKeyId() != null && !key.privateKeyId().isBlank()) {
                jwtBuilder.header().add("kid", key.privateKeyId()).and();
            }
            assertion = jwtBuilder
                    .signWith(parseRsaPrivateKey(key.privateKey()), Jwts.SIG.RS256)
                    .compact();
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(503, "api_error", "Failed to sign service account assertion");
        }

        String form = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + urlEncode(assertion);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VERTEX_TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> upstream = send(account, request);
        byte[] responseBody = readResponseBytes(upstream);
        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            throw new AnthropicApiErrorException(503, "api_error", extractVertexTokenErrorMessage(responseBody));
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String accessToken = textValue(node == null ? null : node.get("access_token"));
            if (accessToken.isEmpty()) {
                throw new AnthropicApiErrorException(503, "api_error", "Service account token response missing access_token");
            }
            return accessToken;
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(503, "api_error", "Failed to parse service account token response");
        }
    }

    private byte[] readResponseBytes(HttpResponse<InputStream> upstream) {
        if (upstream == null) {
            return new byte[0];
        }
        try (InputStream input = upstream.body()) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    private String extractVertexTokenErrorMessage(byte[] body) {
        String fallback = "Service account token request failed";
        if (body == null || body.length == 0) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String errorDescription = textValue(node == null ? null : node.get("error_description"));
            if (!errorDescription.isEmpty()) {
                return errorDescription;
            }
            String error = textValue(node == null ? null : node.get("error"));
            if (!error.isEmpty()) {
                return error;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private PrivateKey parseRsaPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new AnthropicApiErrorException(503, "api_error", "service account json missing private_key");
        }
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(503, "api_error", "Failed to parse service account private key");
        }
    }

    private String resolveVertexProjectId(AdminAccountResponse account) {
        String projectId = stringValue(account.credentials(), "project_id");
        if (projectId != null) {
            return projectId;
        }
        return parseVertexServiceAccountKey(account).projectId();
    }

    private String resolveVertexLocation(AdminAccountResponse account, String model) {
        if (account != null && account.credentials() != null && model != null) {
            Object raw = account.credentials().get("vertex_model_locations");
            if (raw instanceof Map<?, ?> mappings) {
                Object value = mappings.get(model);
                if (value != null) {
                    String mapped = value.toString().trim();
                    if (!mapped.isEmpty()) {
                        return mapped;
                    }
                }
            }
        }
        String location = stringValue(account.credentials(), "location");
        if (location != null) {
            return location;
        }
        location = stringValue(account.credentials(), "vertex_location");
        if (location != null) {
            return location;
        }
        return VERTEX_DEFAULT_LOCATION;
    }

    private String buildVertexAnthropicUrl(String projectId, String location, String model, boolean stream) {
        if (projectId == null || projectId.isBlank()) {
            throw new AnthropicApiErrorException(503, "api_error", "vertex project_id is required");
        }
        String normalizedLocation = (location == null || location.isBlank()) ? VERTEX_DEFAULT_LOCATION : location.trim();
        if (!VERTEX_LOCATION_PATTERN.matcher(normalizedLocation).matches()) {
            throw new AnthropicApiErrorException(503, "api_error", "invalid vertex location: " + normalizedLocation);
        }
        if (model == null || model.isBlank()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
        }
        String action = stream ? "streamRawPredict" : "rawPredict";
        String host = "global".equals(normalizedLocation)
                ? "aiplatform.googleapis.com"
                : normalizedLocation + "-aiplatform.googleapis.com";
        return "https://" + host
                + "/v1/projects/" + urlEncodePath(projectId.trim())
                + "/locations/" + urlEncodePath(normalizedLocation)
                + "/publishers/anthropic/models/" + urlEncodePathPreservingAt(model.trim())
                + ":" + action;
    }

    private byte[] buildVertexAnthropicRequestBody(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            objectNode.remove("model");
            objectNode.put("anthropic_version", VERTEX_ANTHROPIC_VERSION);
            return objectMapper.writeValueAsBytes(objectNode);
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to build Vertex request body");
        }
    }

    private String normalizeVertexAnthropicModel(String model) {
        String normalized = model == null ? "" : model.trim();
        if (normalized.isEmpty() || VERTEX_ANTHROPIC_ALREADY_DATED_ID_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }
        var matcher = VERTEX_ANTHROPIC_DATED_MODEL_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            return matcher.group(1) + "@" + matcher.group(2);
        }
        return normalized;
    }

    private String normalizeClaudeModel(String model) {
        return switch (model == null ? "" : model.trim()) {
            case "claude-sonnet-4-5" -> "claude-sonnet-4-5-20250929";
            case "claude-opus-4-5" -> "claude-opus-4-5-20251101";
            case "claude-haiku-4-5" -> "claude-haiku-4-5-20251001";
            default -> model;
        };
    }

    private VertexServiceAccountKey parseVertexServiceAccountKey(AdminAccountResponse account) {
        if (account == null || account.credentials() == null) {
            throw new AnthropicApiErrorException(503, "api_error", "service account credentials not configured");
        }
        String raw = stringValue(account.credentials(), "service_account_json");
        if (raw == null) {
            raw = stringValue(account.credentials(), "service_account");
        }
        if (raw == null) {
            Object nested = account.credentials().get("service_account_json");
            if (nested == null) {
                nested = account.credentials().get("service_account");
            }
            if (nested instanceof Map<?, ?>) {
                try {
                    raw = objectMapper.writeValueAsString(nested);
                } catch (Exception ex) {
                    throw new AnthropicApiErrorException(503, "api_error", "invalid service account json");
                }
            }
        }
        if (raw == null || raw.isBlank()) {
            throw new AnthropicApiErrorException(503, "api_error", "service_account_json not found in credentials");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            String clientEmail = textValue(node == null ? null : node.get("client_email"));
            String privateKey = textValue(node == null ? null : node.get("private_key"));
            String privateKeyId = textValue(node == null ? null : node.get("private_key_id"));
            String projectId = textValue(node == null ? null : node.get("project_id"));
            if (clientEmail.isEmpty()) {
                throw new AnthropicApiErrorException(503, "api_error", "service account json missing client_email");
            }
            if (privateKey.isEmpty()) {
                throw new AnthropicApiErrorException(503, "api_error", "service account json missing private_key");
            }
            if (projectId.isEmpty()) {
                throw new AnthropicApiErrorException(503, "api_error", "service account json missing project_id");
            }
            return new VertexServiceAccountKey(clientEmail, privateKey, privateKeyId, projectId);
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(503, "api_error", "invalid service account json");
        }
    }

    boolean supportsPlatform(String platform) {
        String normalized = normalize(platform);
        return "anthropic".equals(normalized) || "antigravity".equals(normalized);
    }

    String resolveForwardModel(AdminAccountResponse account, String requestedModel) {
        String mappedModel = resolveMappedModel(account, requestedModel);
        if (isServiceAccountType(account == null ? null : account.type())) {
            return normalizeVertexAnthropicModel(normalizeClaudeModel(mappedModel));
        }
        if (account != null && "anthropic".equalsIgnoreCase(account.platform()) && !"apikey".equalsIgnoreCase(account.type())) {
            return normalizeClaudeModel(mappedModel);
        }
        return mappedModel;
    }

    HttpRequest buildForwardRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            byte[] anthropicBody,
            boolean stream,
            String model
    ) {
        return buildRequest(account, inbound, new MessagePayload(anthropicBody, stream, model));
    }

    HttpResponse<InputStream> sendForwardRequest(AdminAccountResponse account, HttpRequest request) {
        return send(account, request);
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String urlEncodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String urlEncodePathPreservingAt(String value) {
        return urlEncodePath(value).replace("%40", "@");
    }

    private String textValue(JsonNode node) {
        return node == null ? "" : node.asText("").trim();
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isOAuthLikeType(String type) {
        String normalized = normalize(type);
        return "oauth".equals(normalized) || "setup-token".equals(normalized);
    }

    private boolean isServiceAccountType(String type) {
        return "service_account".equals(normalize(type));
    }

    private record MessagePayload(byte[] body, boolean stream, String model) {
    }

    private record VertexServiceAccountKey(
            String clientEmail,
            String privateKey,
            String privateKeyId,
            String projectId
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
        public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
        }
    }
}
