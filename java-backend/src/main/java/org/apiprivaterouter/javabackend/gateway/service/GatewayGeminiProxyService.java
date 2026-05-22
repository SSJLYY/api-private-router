package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.jsonwebtoken.Jwts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.service.AntigravityOAuthService;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.gemini.service.GeminiOAuthGatewayService;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
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
import java.math.BigDecimal;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.KeyFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import java.util.Date;

@Service
public class GatewayGeminiProxyService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration LOCAL_RETRY_THRESHOLD = Duration.ofSeconds(7);
    private static final Duration LOCAL_RETRY_MIN_WAIT = Duration.ofSeconds(1);
    private static final Duration LOCAL_RETRY_MAX_WAIT = Duration.ofSeconds(5);
    private static final Duration MODEL_CAPACITY_RETRY_WAIT = Duration.ofSeconds(1);
    private static final Duration ACCOUNT_TEMP_UNSCHEDULABLE_FALLBACK = Duration.ofMinutes(10);
    private static final int LOCAL_RETRY_MAX_ATTEMPTS = 1;
    private static final String GEMINI_VERTEX_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String GEMINI_VERTEX_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GEMINI_VERTEX_DEFAULT_LOCATION = "us-central1";
    private static final String GEMINI_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String GEMINI_CODE_ASSIST_BASE_URL = "https://cloudcode-pa.googleapis.com";
    private static final String GEMINI_CLI_USER_AGENT = "GeminiCLI/0.1.5 (Windows; AMD64)";
    private static final String GOOGLE_RPC_TYPE_RETRY_INFO = "type.googleapis.com/google.rpc.RetryInfo";
    private static final String GOOGLE_RPC_TYPE_ERROR_INFO = "type.googleapis.com/google.rpc.ErrorInfo";
    private static final String GOOGLE_RPC_REASON_MODEL_CAPACITY_EXHAUSTED = "MODEL_CAPACITY_EXHAUSTED";
    private static final String GOOGLE_RPC_REASON_RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    private static final Pattern GOOGLE_DURATION_TOKEN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)");
    private static final Pattern VERTEX_LOCATION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept",
            "accept-language",
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
    private final GeminiOAuthGatewayService geminiOAuthGatewayService;
    private final AntigravityOAuthService antigravityOAuthService;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public GatewayGeminiProxyService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            GeminiOAuthGatewayService geminiOAuthGatewayService,
            AntigravityOAuthService antigravityOAuthService,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.geminiOAuthGatewayService = geminiOAuthGatewayService;
        this.antigravityOAuthService = antigravityOAuthService;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
    }

    public boolean canHandle(GatewayRuntimeContext runtimeContext, boolean antigravityRoute) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            return false;
        }
        String platform = normalize(runtimeContext.account().platform());
        String type = normalize(runtimeContext.account().type());
        if (antigravityRoute) {
            return "antigravity".equals(platform) && ("apikey".equals(type) || "oauth".equals(type));
        }
        if ("gemini".equals(platform)) {
            return "apikey".equals(type) || "oauth".equals(type) || "service_account".equals(type);
        }
        return "antigravity".equals(platform) && ("apikey".equals(type) || "oauth".equals(type));
    }

    public boolean supportsAction(String action) {
        return "generateContent".equals(action)
                || "streamGenerateContent".equals(action)
                || "countTokens".equals(action);
    }

    public void forward(
            GatewayRuntimeContext runtimeContext,
            HttpServletRequest request,
            HttpServletResponse response,
            byte[] body,
            String model,
            String action
    ) {
        if (runtimeContext.account() == null) {
            throw new ApiErrorException(503, "UNAVAILABLE", "No available Gemini accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new ApiErrorException(503, "UNAVAILABLE", "No available Gemini accounts"));
        String platform = normalize(account.platform());
        if (!"gemini".equals(platform) && !"antigravity".equals(platform)) {
            throw new ApiErrorException(503, "UNAVAILABLE", "No available Gemini accounts");
        }
        if (!supportsAction(action)) {
            throw new ApiErrorException(400, "INVALID_ARGUMENT", "Unsupported Gemini action");
        }

        GeminiPayload payload = preparePayload(body, action);
        HttpResponse<InputStream> upstream = sendWithTransientRetry(account, request, payload.body(), model, action);
        writeResponse(response, upstream, "streamGenerateContent".equals(action));
    }

    private GeminiPayload preparePayload(byte[] body, String action) {
        if (body == null || body.length == 0) {
            throw new ApiErrorException(400, "INVALID_ARGUMENT", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new ApiErrorException(400, "INVALID_ARGUMENT", "Request body must be a JSON object");
            }
            if (!"countTokens".equals(action)) {
                JsonNode contents = objectNode.get("contents");
                if (contents == null || contents.isNull()) {
                    throw new ApiErrorException(400, "INVALID_ARGUMENT", "contents is required");
                }
            } else if (objectNode.isEmpty()) {
                throw new ApiErrorException(400, "INVALID_ARGUMENT", "contents is required");
            }
            return new GeminiPayload(objectMapper.writeValueAsBytes(objectNode));
        } catch (ApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiErrorException(400, "INVALID_ARGUMENT", "Failed to parse request body");
        }
    }

    private HttpRequest buildRequest(AdminAccountResponse account, HttpServletRequest inbound, byte[] body, String model, String action) {
        String mappedModel = resolveMappedModel(account, model);
        String type = normalize(account.type());
        if ("apikey".equals(type)) {
            return buildApiKeyRequest(account, inbound, body, mappedModel, action);
        }
        if ("oauth".equals(type)) {
            return buildOAuthRequest(account, inbound, body, mappedModel, action);
        }
        if ("service_account".equals(type)) {
            return buildServiceAccountRequest(account, inbound, body, mappedModel, action);
        }
        throw new ApiErrorException(501, "UNIMPLEMENTED", "Unsupported Gemini account type");
    }

    private HttpRequest buildApiKeyRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            byte[] body,
            String model,
            String action
    ) {
        String apiKey = stringValue(account.credentials(), "api_key");
        if (apiKey == null) {
            throw new ApiErrorException(503, "UNAVAILABLE", "No upstream credentials available");
        }
        String url = buildGeminiUrl(account, model, action);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey);

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("x-goog-api-key", apiKey);
        if ("streamGenerateContent".equals(action)) {
            builder.setHeader("Accept", "text/event-stream");
        }
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
        }
        return builder.build();
    }

    private HttpRequest buildOAuthRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            byte[] body,
            String model,
            String action
    ) {
        if (!supportsGeminiOauthPlatform(account)) {
            throw new ApiErrorException(501, "UNIMPLEMENTED", "Gemini OAuth forwarding is only available for gemini or antigravity OAuth accounts");
        }
        String accessToken = refreshOauthAccessTokenIfNeeded(account);
        String projectId = stringValue(account.credentials(), "project_id");
        String url;
        HttpRequest.BodyPublisher requestBody;

        if (projectId == null) {
            String baseUrl = resolveGeminiBaseUrl(account, GEMINI_DEFAULT_BASE_URL);
            url = buildGeminiUrl(trimTrailingSlash(baseUrl), model, action);
            requestBody = HttpRequest.BodyPublishers.ofByteArray(body);
        } else {
            url = buildCodeAssistUrl(action);
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("model", model);
            wrapped.put("project", projectId);
            wrapped.put("request", readJsonObject(body));
            requestBody = HttpRequest.BodyPublishers.ofString(writeJson(wrapped), StandardCharsets.UTF_8);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(requestBody)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken);

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Authorization", "Bearer " + accessToken);
        if ("streamGenerateContent".equals(action)) {
            builder.setHeader("Accept", "text/event-stream");
        }
        if (projectId != null) {
            builder.setHeader("User-Agent", GEMINI_CLI_USER_AGENT);
        } else {
            String customUserAgent = stringValue(account.credentials(), "user_agent");
            if (customUserAgent != null) {
                builder.setHeader("User-Agent", customUserAgent);
            }
        }
        return builder.build();
    }

    private HttpRequest buildServiceAccountRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            byte[] body,
            String model,
            String action
    ) {
        if (!"gemini".equalsIgnoreCase(account.platform())) {
            throw new ApiErrorException(501, "UNIMPLEMENTED", "Gemini service account forwarding is only available for gemini accounts");
        }
        String accessToken = exchangeVertexServiceAccountAccessToken(account);
        String projectId = resolveVertexProjectId(account);
        String location = resolveVertexLocation(account, model);
        String url = buildVertexGeminiUrl(projectId, location, model, action);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken);

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Authorization", "Bearer " + accessToken);
        if ("streamGenerateContent".equals(action)) {
            builder.setHeader("Accept", "text/event-stream");
        }
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
        }
        return builder.build();
    }

    private HttpResponse<InputStream> send(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiErrorException(502, "UNAVAILABLE", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new ApiErrorException(502, "UNAVAILABLE", "Upstream request failed");
        }
    }

    private HttpResponse<InputStream> sendWithTransientRetry(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            byte[] body,
            String model,
            String action
    ) {
        String mappedModel = resolveMappedModel(account, model);
        for (int attempt = 0; ; attempt++) {
            HttpRequest upstreamRequest = buildRequest(account, inbound, body, model, action);
            HttpResponse<InputStream> upstream = send(account, upstreamRequest);
            int status = upstream.statusCode();
            if (status != 429 && status != 503) {
                return upstream;
            }
            BufferedUpstreamError buffered = bufferUpstreamError(upstream);
            LocalRetryDecision retryDecision = decideLocalRetry(buffered.statusCode(), buffered.body());
            if (retryDecision.shouldRetryLocally() && attempt < LOCAL_RETRY_MAX_ATTEMPTS) {
                sleepSafely(retryDecision.waitDuration());
                continue;
            }
            persistRecoverableSignal(account, mappedModel, buffered.statusCode(), buffered.body());
            throw new RecoverableUpstreamException(buffered.statusCode(), readUpstreamErrorMessage(buffered.body()));
        }
    }

    private void writeResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, boolean stream) {
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
                    .orElse(stream ? "text/event-stream" : "application/json"));
        }
        if (stream) {
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
        }
        try (InputStream input = upstream.body()) {
            ServletOutputStream output = response.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (stream) {
                    output.flush();
                }
            }
            response.flushBuffer();
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to write upstream response");
        }
    }

    private BufferedUpstreamError bufferUpstreamError(HttpResponse<InputStream> upstream) {
        if (upstream == null) {
            return new BufferedUpstreamError(0, new byte[0]);
        }
        try (InputStream input = upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            return new BufferedUpstreamError(upstream.statusCode(), body);
        } catch (IOException ignored) {
            return new BufferedUpstreamError(upstream.statusCode(), new byte[0]);
        }
    }

    private LocalRetryDecision decideLocalRetry(int statusCode, byte[] body) {
        GoogleRpcRetryInfo retryInfo = parseGoogleRpcRetryInfo(body, statusCode);
        if (retryInfo == null) {
            return LocalRetryDecision.noRetry();
        }
        if (retryInfo.modelCapacityExhausted()) {
            return LocalRetryDecision.retry(MODEL_CAPACITY_RETRY_WAIT);
        }
        if (!retryInfo.rateLimitExceeded() || retryInfo.retryDelay() == null) {
            return LocalRetryDecision.noRetry();
        }
        if (retryInfo.retryDelay().compareTo(LOCAL_RETRY_THRESHOLD) >= 0) {
            return LocalRetryDecision.noRetry();
        }
        Duration waitDuration = retryInfo.retryDelay();
        if (waitDuration.compareTo(LOCAL_RETRY_MIN_WAIT) < 0) {
            waitDuration = LOCAL_RETRY_MIN_WAIT;
        }
        if (waitDuration.compareTo(LOCAL_RETRY_MAX_WAIT) > 0) {
            waitDuration = LOCAL_RETRY_MAX_WAIT;
        }
        return LocalRetryDecision.retry(waitDuration);
    }

    private void persistRecoverableSignal(AdminAccountResponse account, String model, int statusCode, byte[] body) {
        if (account == null || account.id() <= 0 || (statusCode != 429 && statusCode != 503)) {
            return;
        }
        GoogleRpcRetryInfo retryInfo = parseGoogleRpcRetryInfo(body, statusCode);
        if (retryInfo == null) {
            return;
        }
        if (retryInfo.rateLimitExceeded()) {
            Instant resetAt = Instant.now().plus(resolveRateLimitResetDelay(retryInfo.retryDelay()));
            accountRepository.setRateLimited(account.id(), resetAt);
            return;
        }
        if (retryInfo.modelCapacityExhausted()) {
            Instant until = Instant.now().plus(resolveTempUnschedulableDuration(retryInfo.retryDelay()));
            accountRepository.setTempUnschedulable(
                    account.id(),
                    until,
                    "MODEL_CAPACITY_EXHAUSTED for model " + (model == null || model.isBlank() ? "unknown" : model)
            );
            accountRepository.setOverloaded(account.id(), until);
        }
    }

    private Duration resolveRateLimitResetDelay(Duration retryDelay) {
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            return ACCOUNT_TEMP_UNSCHEDULABLE_FALLBACK;
        }
        return retryDelay;
    }

    private Duration resolveTempUnschedulableDuration(Duration retryDelay) {
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            return ACCOUNT_TEMP_UNSCHEDULABLE_FALLBACK;
        }
        return retryDelay.compareTo(ACCOUNT_TEMP_UNSCHEDULABLE_FALLBACK) > 0
                ? retryDelay
                : ACCOUNT_TEMP_UNSCHEDULABLE_FALLBACK;
    }

    private GoogleRpcRetryInfo parseGoogleRpcRetryInfo(byte[] body, int statusCode) {
        if (body == null || body.length == 0 || (statusCode != 429 && statusCode != 503)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root == null ? null : root.get("error");
            if (error == null || !error.isObject()) {
                return null;
            }
            String errorStatus = textValue(error.get("status"));
            JsonNode details = error.get("details");
            if (details == null || !details.isArray()) {
                return null;
            }
            String reason = "";
            Duration retryDelay = null;
            for (JsonNode detail : details) {
                if (detail == null || !detail.isObject()) {
                    continue;
                }
                String detailType = textValue(detail.get("@type"));
                if (GOOGLE_RPC_TYPE_ERROR_INFO.equals(detailType)) {
                    String detailReason = textValue(detail.get("reason"));
                    if (!detailReason.isEmpty()) {
                        reason = detailReason;
                    }
                    continue;
                }
                if (GOOGLE_RPC_TYPE_RETRY_INFO.equals(detailType)) {
                    Duration parsedDelay = parseGoogleRetryDelay(textValue(detail.get("retryDelay")));
                    if (parsedDelay != null && !parsedDelay.isNegative() && !parsedDelay.isZero()) {
                        retryDelay = parsedDelay;
                    }
                }
            }
            boolean rateLimitExceeded = statusCode == 429
                    && "RESOURCE_EXHAUSTED".equalsIgnoreCase(errorStatus)
                    && GOOGLE_RPC_REASON_RATE_LIMIT_EXCEEDED.equalsIgnoreCase(reason);
            boolean modelCapacityExhausted = statusCode == 503
                    && "UNAVAILABLE".equalsIgnoreCase(errorStatus)
                    && GOOGLE_RPC_REASON_MODEL_CAPACITY_EXHAUSTED.equalsIgnoreCase(reason);
            if (!rateLimitExceeded && !modelCapacityExhausted) {
                return null;
            }
            return new GoogleRpcRetryInfo(retryDelay, rateLimitExceeded, modelCapacityExhausted);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Duration parseGoogleRetryDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = GOOGLE_DURATION_TOKEN.matcher(normalized);
        int currentIndex = 0;
        long totalNanos = 0L;
        boolean matched = false;
        while (matcher.find()) {
            if (matcher.start() != currentIndex) {
                return null;
            }
            matched = true;
            currentIndex = matcher.end();
            BigDecimal value = new BigDecimal(matcher.group(1));
            long unitNanos = switch (matcher.group(2)) {
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                case "h" -> 3_600_000_000_000L;
                default -> 0L;
            };
            if (unitNanos <= 0L) {
                return null;
            }
            totalNanos = Math.addExact(totalNanos, value.multiply(BigDecimal.valueOf(unitNanos)).longValue());
        }
        if (!matched || currentIndex != normalized.length()) {
            return null;
        }
        return Duration.ofNanos(totalNanos);
    }

    private void sleepSafely(Duration waitDuration) {
        if (waitDuration == null || waitDuration.isZero() || waitDuration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(waitDuration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiErrorException(502, "UNAVAILABLE", "Upstream retry interrupted");
        }
    }

    private String readUpstreamErrorMessage(byte[] body) {
        String message = "Gemini upstream request failed";
        try {
            JsonNode root = body.length == 0 ? null : objectMapper.readTree(body);
            if (root == null) {
                return message;
            }
            JsonNode error = root.get("error");
            String upstreamMessage = textValue(error == null ? null : error.get("message"));
            if (upstreamMessage.isEmpty()) {
                upstreamMessage = textValue(root.get("message"));
            }
            if (!upstreamMessage.isEmpty()) {
                return upstreamMessage;
            }
        } catch (Exception ignored) {
        }
        return message;
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

    private String buildGeminiUrl(AdminAccountResponse account, String model, String action) {
        String baseUrl = resolveGeminiBaseUrl(account, GEMINI_DEFAULT_BASE_URL);
        String normalized = trimTrailingSlash(baseUrl);
        if (isAntigravityApiKey(account) && !normalized.toLowerCase(Locale.ROOT).endsWith("/antigravity")) {
            normalized = normalized + "/antigravity";
        }
        return buildGeminiUrl(normalized, model, action);
    }

    private String buildGeminiUrl(String baseUrl, String model, String action) {
        String normalized = trimTrailingSlash(baseUrl);
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
        String url = normalized + "/v1beta/models/" + encodedModel + ":" + action;
        if ("streamGenerateContent".equals(action)) {
            url += "?alt=sse";
        }
        return url;
    }

    private String buildCodeAssistUrl(String action) {
        String url = trimTrailingSlash(GEMINI_CODE_ASSIST_BASE_URL) + "/v1internal:" + action;
        if ("streamGenerateContent".equals(action)) {
            url += "?alt=sse";
        }
        return url;
    }

    private boolean isAntigravityApiKey(AdminAccountResponse account) {
        return account != null
                && "antigravity".equalsIgnoreCase(account.platform())
                && "apikey".equalsIgnoreCase(account.type());
    }

    private boolean supportsGeminiOauthPlatform(AdminAccountResponse account) {
        if (account == null) {
            return false;
        }
        String platform = normalize(account.platform());
        return "gemini".equals(platform) || "antigravity".equals(platform);
    }

    private String refreshOauthAccessTokenIfNeeded(AdminAccountResponse account) {
        String accessToken = stringValue(account.credentials(), "access_token");
        if (accessToken == null) {
            throw new ApiErrorException(503, "UNAVAILABLE", "No access token available");
        }
        String expiresAt = stringValue(account.credentials(), "expires_at");
        if (expiresAt == null) {
            return accessToken;
        }
        try {
            long expiresAtEpoch = Long.parseLong(expiresAt);
            long now = System.currentTimeMillis() / 1000L;
            if (expiresAtEpoch - now > 300) {
                return accessToken;
            }
        } catch (NumberFormatException ignored) {
            return accessToken;
        }

        try {
            Map<String, Object> merged = new LinkedHashMap<>(account.credentials() == null ? Map.of() : account.credentials());
            String platform = normalize(account.platform());
            String refreshedAccessToken;
            if ("antigravity".equals(platform)) {
                AntigravityOAuthTokenResponse token = antigravityOAuthService.refreshAccountToken(account);
                merged.putAll(antigravityOAuthService.buildAccountCredentials(token));
                refreshedAccessToken = token.access_token();
            } else {
                GeminiOAuthTokenResponse token = geminiOAuthGatewayService.refreshAccountToken(account, loadProxy(account));
                merged.putAll(geminiOAuthGatewayService.buildAccountCredentials(token));
                refreshedAccessToken = token.access_token();
            }
            accountRepository.updateAccountColumns(
                    account.id(),
                    null,
                    false,
                    null,
                    null,
                    true,
                    merged,
                    false,
                    Map.of(),
                    false,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null
            );
            return refreshedAccessToken;
        } catch (IllegalArgumentException ex) {
            throw new ApiErrorException(503, "UNAVAILABLE", ex.getMessage());
        }
    }

    private AdminProxyResponse loadProxy(AdminAccountResponse account) {
        if (account == null || account.proxy_id() == null || account.proxy_id() <= 0) {
            return null;
        }
        return proxyRepository.getProxy(account.proxy_id()).orElse(null);
    }

    private String resolveGeminiBaseUrl(AdminAccountResponse account, String defaultBaseUrl) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null) {
            return defaultBaseUrl;
        }
        baseUrl = upstreamUrlGuard.normalizeAccountBaseUrl(account.platform(), account.type(), baseUrl, defaultBaseUrl);
        if (isAntigravityApiKey(account)) {
            return trimTrailingSlash(baseUrl) + "/antigravity";
        }
        return baseUrl;
    }

    private String exchangeVertexServiceAccountAccessToken(AdminAccountResponse account) {
        VertexServiceAccountKey key = parseVertexServiceAccountKey(account);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(1));
        String assertion;
        try {
            assertion = Jwts.builder()
                    .issuer(key.clientEmail())
                    .claim("scope", GEMINI_VERTEX_SCOPE)
                    .audience().add(GEMINI_VERTEX_TOKEN_URL).and()
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(expiresAt))
                    .signWith(parseRsaPrivateKey(key.privateKey()), Jwts.SIG.RS256)
                    .header().add("kid", key.privateKeyId()).and()
                    .compact();
        } catch (ApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiErrorException(503, "UNAVAILABLE", "Failed to sign service account assertion");
        }

        String form = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + urlEncode(assertion);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_VERTEX_TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> upstream = send(account, request);
        byte[] responseBody = readResponseBytes(upstream);
        if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
            throw new ApiErrorException(503, "UNAVAILABLE", extractVertexTokenErrorMessage(responseBody));
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String accessToken = textValue(node == null ? null : node.get("access_token"));
            if (accessToken.isEmpty()) {
                throw new ApiErrorException(503, "UNAVAILABLE", "Service account token response missing access_token");
            }
            return accessToken;
        } catch (ApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiErrorException(503, "UNAVAILABLE", "Failed to parse service account token response");
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
            throw new ApiErrorException(503, "UNAVAILABLE", "service account json missing private_key");
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
            throw new ApiErrorException(503, "UNAVAILABLE", "Failed to parse service account private key");
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
        return GEMINI_VERTEX_DEFAULT_LOCATION;
    }

    private String buildVertexGeminiUrl(String projectId, String location, String model, String action) {
        if (projectId == null || projectId.isBlank()) {
            throw new ApiErrorException(503, "UNAVAILABLE", "vertex project_id is required");
        }
        String normalizedLocation = (location == null || location.isBlank()) ? GEMINI_VERTEX_DEFAULT_LOCATION : location.trim();
        if (!VERTEX_LOCATION_PATTERN.matcher(normalizedLocation).matches()) {
            throw new ApiErrorException(503, "UNAVAILABLE", "invalid vertex location: " + normalizedLocation);
        }
        if (model == null || model.isBlank()) {
            throw new ApiErrorException(400, "INVALID_ARGUMENT", "model is required");
        }
        String host = "global".equals(normalizedLocation)
                ? "aiplatform.googleapis.com"
                : normalizedLocation + "-aiplatform.googleapis.com";
        String url = "https://" + host
                + "/v1/projects/" + urlEncodePath(projectId.trim())
                + "/locations/" + urlEncodePath(normalizedLocation)
                + "/publishers/google/models/" + urlEncodePath(model.trim())
                + ":" + action;
        if ("streamGenerateContent".equals(action)) {
            url += "?alt=sse";
        }
        return url;
    }

    private VertexServiceAccountKey parseVertexServiceAccountKey(AdminAccountResponse account) {
        if (account == null || account.credentials() == null) {
            throw new ApiErrorException(503, "UNAVAILABLE", "service account credentials not configured");
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
                raw = writeJson(nested);
            }
        }
        if (raw == null || raw.isBlank()) {
            throw new ApiErrorException(503, "UNAVAILABLE", "service_account_json not found in credentials");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            String clientEmail = textValue(node == null ? null : node.get("client_email"));
            String privateKey = textValue(node == null ? null : node.get("private_key"));
            String privateKeyId = textValue(node == null ? null : node.get("private_key_id"));
            String projectId = textValue(node == null ? null : node.get("project_id"));
            if (clientEmail.isEmpty()) {
                throw new ApiErrorException(503, "UNAVAILABLE", "service account json missing client_email");
            }
            if (privateKey.isEmpty()) {
                throw new ApiErrorException(503, "UNAVAILABLE", "service account json missing private_key");
            }
            if (projectId.isEmpty()) {
                throw new ApiErrorException(503, "UNAVAILABLE", "service account json missing project_id");
            }
            return new VertexServiceAccountKey(clientEmail, privateKey, privateKeyId, projectId);
        } catch (ApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiErrorException(503, "UNAVAILABLE", "invalid service account json");
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String urlEncodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
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

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private Map<String, Object> readJsonObject(byte[] payload) {
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (IOException ex) {
            throw new ApiErrorException(400, "INVALID_ARGUMENT", "Failed to parse request body");
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new ApiErrorException(500, "INTERNAL", "Failed to encode upstream request");
        }
    }

    private record GeminiPayload(byte[] body) {
    }

    private record BufferedUpstreamError(int statusCode, byte[] body) {
    }

    private record GoogleRpcRetryInfo(Duration retryDelay, boolean rateLimitExceeded, boolean modelCapacityExhausted) {
    }

    private record LocalRetryDecision(boolean shouldRetryLocally, Duration waitDuration) {
        private static LocalRetryDecision noRetry() {
            return new LocalRetryDecision(false, Duration.ZERO);
        }

        private static LocalRetryDecision retry(Duration waitDuration) {
            return new LocalRetryDecision(true, waitDuration == null ? Duration.ZERO : waitDuration);
        }
    }

    private record VertexServiceAccountKey(
            String clientEmail,
            String privateKey,
            String privateKeyId,
            String projectId
    ) {
    }

    public static final class RecoverableUpstreamException extends RuntimeException {
        private final int statusCode;

        private RecoverableUpstreamException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
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
