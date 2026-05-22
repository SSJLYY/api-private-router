package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class GatewayOpenAiResponsesService {

    private static final String CHATGPT_CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
    private static final String DEFAULT_INSTRUCTIONS = "You are a helpful coding assistant.";
    private static final Set<String> OPENAI_CODEX_OAUTH_UNSUPPORTED_FIELDS = Set.of(
            "max_output_tokens",
            "max_completion_tokens",
            "temperature",
            "top_p",
            "frequency_penalty",
            "presence_penalty",
            "user",
            "metadata",
            "prompt_cache_retention",
            "safety_identifier",
            "stream_options"
    );
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Set<String> OPENAI_CHATGPT_INTERNAL_UNSUPPORTED_FIELDS = Set.of(
            "prompt_cache_retention",
            "safety_identifier"
    );
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept",
            "accept-encoding",
            "accept-language",
            "conversation_id",
            "content-type",
            "openai-beta",
            "openai-organization",
            "openai-project",
            "originator",
            "session_id",
            "user-agent",
            "version",
            "x-codex-turn-metadata",
            "x-codex-turn-state"
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
    private final GatewayOpenAiAccountRoutingPolicy routingPolicy;
    private final GatewayOpenAiFastPolicyService fastPolicyService;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public GatewayOpenAiResponsesService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiFastPolicyService fastPolicyService,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.routingPolicy = routingPolicy;
        this.fastPolicyService = fastPolicyService;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
    }

    public void forward(GatewayRuntimeContext runtimeContext, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        if (runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new OpenAiApiErrorException(503, "api_error", "No available compatible accounts"));
        if (!"openai".equalsIgnoreCase(account.platform())) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible OpenAI accounts");
        }
        if (!"apikey".equalsIgnoreCase(account.type())
                && !"oauth".equalsIgnoreCase(account.type())
                && !"setup-token".equalsIgnoreCase(account.type())) {
            throw new OpenAiApiErrorException(501, "unsupported_error", "OpenAI responses forwarding is not supported for this account type yet");
        }

        boolean compactRequest = isCompactRequest(request);
        boolean passthrough = runtimeContext.account() != null && routingPolicy.isPassthroughEnabled(runtimeContext.account());
        ResponsesPayload payload = preparePayload(
                body,
                account,
                compactRequest,
                passthrough,
                runtimeContext,
                isOfficialCodexClient(request)
        );
        HttpRequest upstreamRequest = buildRequest(account, request, payload.body(), payload.stream(), resolveRequestSuffix(request));
        HttpResponse<InputStream> upstream = send(account, upstreamRequest);
        throwIfFailoverRequired(upstream);
        writeResponse(response, upstream, payload.stream());
    }

    private ResponsesPayload preparePayload(
            byte[] body,
            AdminAccountResponse account,
            boolean compactRequest,
            boolean passthrough,
            GatewayRuntimeContext runtimeContext,
            boolean officialCodexClient
    ) {
        if (body == null || body.length == 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            ObjectNode effectiveNode = compactRequest ? normalizeCompactRequestBody(objectNode) : objectNode.deepCopy();
            normalizeHttpRequestBody(effectiveNode, account, compactRequest, passthrough, officialCodexClient);
            JsonNode modelNode = effectiveNode.get("model");
            if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "model is required");
            }
            String requestedModel = modelNode.asText().trim();
            String upstreamModel = resolveUpstreamModelForRequest(account, requestedModel, compactRequest);
            if (!upstreamModel.equals(requestedModel)) {
                effectiveNode.put("model", upstreamModel);
            }
            normalizeReasoningEffort(effectiveNode);
            normalizeTokenCompatibility(effectiveNode, account, officialCodexClient);
            normalizeVerbosityCompatibility(effectiveNode, upstreamModel);
            sanitizeEmptyBase64InputImages(effectiveNode);
            enforceImageGenerationPermission(effectiveNode, requestedModel, runtimeContext);
            JsonNode serviceTierNode = effectiveNode.get("service_tier");
            if (serviceTierNode != null && serviceTierNode.isTextual()) {
                String normalizedTier = fastPolicyService.normalizeServiceTier(serviceTierNode.asText());
                if (normalizedTier == null) {
                    effectiveNode.remove("service_tier");
                } else {
                    GatewayOpenAiFastPolicyService.FastPolicyApplyResult fastPolicyResult =
                            fastPolicyService.applyToRequestBody(account, upstreamModel, effectiveNode, normalizedTier);
                    if (fastPolicyResult.action() == GatewayOpenAiFastPolicyService.Action.BLOCK) {
                        throw new OpenAiApiErrorException(403, "permission_error", fastPolicyResult.blockMessage());
                    }
                    effectiveNode = fastPolicyResult.payload();
                }
            }
            return new ResponsesPayload(objectMapper.writeValueAsBytes(effectiveNode), effectiveNode.path("stream").asBoolean(false));
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private ObjectNode normalizeCompactRequestBody(ObjectNode source) {
        ObjectNode normalized = objectMapper.createObjectNode();
        copyIfPresent(source, normalized, "model");
        copyIfPresent(source, normalized, "input");
        copyIfPresent(source, normalized, "instructions");
        copyIfPresent(source, normalized, "tools");
        copyIfPresent(source, normalized, "parallel_tool_calls");
        copyIfPresent(source, normalized, "reasoning");
        copyIfPresent(source, normalized, "text");
        copyIfPresent(source, normalized, "previous_response_id");
        return normalized;
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null) {
            target.set(fieldName, value);
        }
    }

    private void normalizeHttpRequestBody(
            ObjectNode effectiveNode,
            AdminAccountResponse account,
            boolean compactRequest,
            boolean passthrough,
            boolean officialCodexClient
    ) {
        if (effectiveNode == null) {
            return;
        }
        for (String unsupportedField : OPENAI_CHATGPT_INTERNAL_UNSUPPORTED_FIELDS) {
            effectiveNode.remove(unsupportedField);
        }
        effectiveNode.remove("previous_response_id");
        String type = normalize(account == null ? null : account.type());
        if (isOauthLikeType(type)) {
            normalizeOauthRequestBody(effectiveNode, compactRequest, passthrough, officialCodexClient);
        } else if (effectiveNode.path("stream").asBoolean(false)) {
            effectiveNode.put("stream", true);
        }
        if (!compactRequest && !passthrough && isInstructionsEmpty(effectiveNode)) {
            effectiveNode.put("instructions", DEFAULT_INSTRUCTIONS);
        }
    }

    private void normalizeOauthRequestBody(
            ObjectNode effectiveNode,
            boolean compactRequest,
            boolean passthrough,
            boolean officialCodexClient
    ) {
        if (effectiveNode == null) {
            return;
        }
        if (passthrough
                && !officialCodexClient
                && looksLikeCodexModel(text(effectiveNode.get("model")))
                && isInstructionsEmpty(effectiveNode)) {
            throw new OpenAiApiErrorException(403, "forbidden_error", "OpenAI codex passthrough requires a non-empty instructions field");
        }
        if (!passthrough) {
            extractSystemMessagesFromInput(effectiveNode);
        }
        for (String unsupportedField : OPENAI_CODEX_OAUTH_UNSUPPORTED_FIELDS) {
            effectiveNode.remove(unsupportedField);
        }
        if (compactRequest) {
            effectiveNode.remove("store");
            effectiveNode.remove("stream");
            return;
        }
        effectiveNode.put("store", false);
        effectiveNode.put("stream", true);
    }

    private void normalizeReasoningEffort(ObjectNode requestBody) {
        if (requestBody == null) {
            return;
        }
        JsonNode reasoningNode = requestBody.get("reasoning");
        if (!(reasoningNode instanceof ObjectNode reasoningObject)) {
            return;
        }
        String rawEffort = text(reasoningObject.get("effort"));
        if (rawEffort.isEmpty()) {
            return;
        }
        String normalizedEffort = normalizeOpenAiReasoningEffort(rawEffort);
        if (normalizedEffort.isEmpty()) {
            reasoningObject.remove("effort");
            return;
        }
        if (!normalizedEffort.equals(rawEffort)) {
            reasoningObject.put("effort", normalizedEffort);
        }
    }

    private void normalizeTokenCompatibility(
            ObjectNode requestBody,
            AdminAccountResponse account,
            boolean officialCodexClient
    ) {
        if (requestBody == null || officialCodexClient) {
            return;
        }
        String accountType = normalize(account == null ? null : account.type());
        if ("apikey".equals(accountType)) {
            requestBody.remove("max_output_tokens");
            requestBody.remove("max_completion_tokens");
            return;
        }
        if (!isOauthLikeType(accountType)) {
            requestBody.remove("max_output_tokens");
            requestBody.remove("max_completion_tokens");
        }
    }

    private void normalizeVerbosityCompatibility(ObjectNode requestBody, String upstreamModel) {
        if (requestBody == null || supportsVerbosity(upstreamModel)) {
            return;
        }
        JsonNode textNode = requestBody.get("text");
        if (!(textNode instanceof ObjectNode textObject)) {
            return;
        }
        textObject.remove("verbosity");
        if (textObject.isEmpty()) {
            requestBody.remove("text");
        }
    }

    private void extractSystemMessagesFromInput(ObjectNode requestBody) {
        if (requestBody == null) {
            return;
        }
        JsonNode inputNode = requestBody.get("input");
        if (!(inputNode instanceof ArrayNode inputItems) || inputItems.isEmpty()) {
            return;
        }
        ArrayNode retained = objectMapper.createArrayNode();
        List<String> systemTexts = new ArrayList<>();
        for (JsonNode itemNode : inputItems) {
            if (!(itemNode instanceof ObjectNode itemObject)) {
                retained.add(itemNode);
                continue;
            }
            if (!"system".equals(normalize(text(itemObject.get("role"))))) {
                retained.add(itemObject);
                continue;
            }
            String extracted = extractTextFromContent(itemObject.get("content"));
            if (!extracted.isBlank()) {
                systemTexts.add(extracted);
            }
        }
        if (systemTexts.isEmpty()) {
            return;
        }
        String extractedInstructions = String.join("\n\n", systemTexts);
        String existingInstructions = text(requestBody.get("instructions"));
        if (existingInstructions.isBlank()) {
            requestBody.put("instructions", extractedInstructions);
        } else {
            requestBody.put("instructions", extractedInstructions + "\n\n" + existingInstructions);
        }
        requestBody.set("input", retained);
    }

    private String extractTextFromContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (!(contentNode instanceof ArrayNode contentItems)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode partNode : contentItems) {
            if (!(partNode instanceof ObjectNode partObject)) {
                continue;
            }
            String type = text(partObject.get("type"));
            if (!"text".equals(type) && !"input_text".equals(type) && !"output_text".equals(type)) {
                continue;
            }
            String text = partObject.path("text").asText("");
            if (!text.isEmpty()) {
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private boolean isInstructionsEmpty(ObjectNode requestBody) {
        if (requestBody == null) {
            return true;
        }
        JsonNode instructions = requestBody.get("instructions");
        return instructions == null
                || instructions.isNull()
                || !instructions.isTextual()
                || instructions.asText().trim().isEmpty();
    }

    private HttpRequest buildRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            byte[] body,
            boolean stream,
            String suffix
    ) {
        String authToken = resolveAuthToken(account);
        if (authToken == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No upstream credentials available");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(buildResponsesUrl(account, suffix)))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .header("Accept", resolveAcceptHeader(account, stream, suffix));

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Authorization", "Bearer " + authToken);
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Accept", resolveAcceptHeader(account, stream, suffix));
        applyOAuthHeaders(builder, account, suffix);
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
            throw new OpenAiApiErrorException(502, "upstream_error", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "upstream_error", "Upstream request failed");
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

    private void throwIfFailoverRequired(HttpResponse<InputStream> upstream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status != 429 && status != 529) {
            return;
        }
        throw new OpenAiUpstreamFailoverException(status, "rate_limit_error", readOpenAiErrorMessage(upstream));
    }

    private String readOpenAiErrorMessage(HttpResponse<InputStream> upstream) {
        String message = "OpenAI upstream request failed";
        try (InputStream input = upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isObject()) {
                String upstreamMessage = text(error.get("message"));
                if (!upstreamMessage.isEmpty()) {
                    message = upstreamMessage;
                }
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

    public String buildResponsesUrl(AdminAccountResponse account, String suffix) {
        if (account != null && isOauthLikeType(account.type())) {
            return appendPathSuffix(CHATGPT_CODEX_RESPONSES_URL, suffix);
        }
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com";
        }
        baseUrl = upstreamUrlGuard.normalizeAccountBaseUrl(account.platform(), account.type(), baseUrl, "https://api.openai.com");
        String normalized = trimTrailingSlash(baseUrl);
        if (!normalized.endsWith("/responses")) {
            if (normalized.endsWith("/v1")) {
                normalized = normalized + "/responses";
            } else {
                normalized = normalized + "/v1/responses";
            }
        }
        String normalizedSuffix = suffix == null ? "" : suffix.trim();
        if (normalizedSuffix.isEmpty() || "/".equals(normalizedSuffix)) {
            return normalized;
        }
        if (!normalizedSuffix.startsWith("/")) {
            normalizedSuffix = "/" + normalizedSuffix;
        }
        return normalized + normalizedSuffix;
    }

    private String appendPathSuffix(String baseUrl, String suffix) {
        String normalizedBase = trimTrailingSlash(baseUrl);
        String normalizedSuffix = suffix == null ? "" : suffix.trim();
        if (normalizedSuffix.isEmpty() || "/".equals(normalizedSuffix)) {
            return normalizedBase;
        }
        if (!normalizedSuffix.startsWith("/")) {
            normalizedSuffix = "/" + normalizedSuffix;
        }
        return normalizedBase + normalizedSuffix;
    }

    public String resolveRequestSuffix(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        return resolveRequestSuffix(path);
    }

    public String resolveRequestSuffix(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String trimmed = path.trim();
        int idx = trimmed.lastIndexOf("/responses");
        if (idx < 0) {
            return "";
        }
        String suffix = trimmed.substring(idx + "/responses".length());
        if (suffix.isEmpty() || "/".equals(suffix)) {
            return "";
        }
        return suffix;
    }

    private String resolveAuthToken(AdminAccountResponse account) {
        if (account == null) {
            return null;
        }
        if (isOauthLikeType(account.type())) {
            return stringValue(account.credentials(), "access_token");
        }
        return stringValue(account.credentials(), "api_key");
    }

    private String resolveAcceptHeader(AdminAccountResponse account, boolean stream, String suffix) {
        if (account != null
                && isOauthLikeType(account.type())
                && isCompactSuffix(suffix)) {
            return "application/json";
        }
        return stream ? "text/event-stream" : "application/json";
    }

    private boolean isCompactSuffix(String suffix) {
        String normalized = suffix == null ? "" : suffix.trim().toLowerCase(Locale.ROOT);
        return "/compact".equals(normalized) || normalized.startsWith("/compact/");
    }

    private void applyOAuthHeaders(HttpRequest.Builder builder, AdminAccountResponse account, String suffix) {
        if (builder == null || account == null || !isOauthLikeType(account.type())) {
            return;
        }
        builder.setHeader("Host", "chatgpt.com");
        builder.setHeader("OpenAI-Beta", "responses=experimental");
        String chatgptAccountId = stringValue(account.credentials(), "chatgpt_account_id");
        if (chatgptAccountId != null) {
            builder.setHeader("chatgpt-account-id", chatgptAccountId);
        }
        if (isCompactSuffix(suffix)) {
            builder.setHeader("Originator", "codex_cli_rs");
            builder.setHeader("Version", "1.0.0");
            String sessionId = compactProbeSessionId(account.id());
            builder.setHeader("Session_ID", sessionId);
            builder.setHeader("Conversation_ID", sessionId);
        }
    }

    private String compactProbeSessionId(long keyId) {
        return "java-responses-compact-" + keyId;
    }

    private boolean isCompactRequest(HttpServletRequest request) {
        String suffix = resolveRequestSuffix(request).toLowerCase(Locale.ROOT);
        return suffix.equals("/compact") || suffix.startsWith("/compact/");
    }

    public String resolveMappedModel(AdminAccountResponse account, String requestedModel) {
        return resolveFromMapping(extractStringMap(account.credentials() == null ? null : account.credentials().get("model_mapping")), requestedModel);
    }

    public String resolveMappedModelForRequest(AdminAccountResponse account, String requestedModel, boolean compactRequest) {
        String mapped = resolveMappedModel(account, requestedModel);
        if (!compactRequest) {
            return mapped;
        }
        return resolveCompactMappedModel(account, mapped);
    }

    public String resolveUpstreamModelForRequest(AdminAccountResponse account, String requestedModel, boolean compactRequest) {
        String mappedModel = resolveMappedModel(account, requestedModel);
        if (!compactRequest) {
            return normalizeOpenAiModelForUpstream(account, mappedModel);
        }
        String compactMappedModel = resolveCompactMappedModel(account, mappedModel);
        if (!compactMappedModel.equals(mappedModel)) {
            return compactMappedModel;
        }
        return normalizeOpenAiModelForUpstream(account, compactMappedModel);
    }

    public String resolveCompactMappedModel(AdminAccountResponse account, String requestedModel) {
        String mapped = resolveFromMapping(extractStringMap(account.credentials() == null ? null : account.credentials().get("compact_model_mapping")), requestedModel);
        return mapped == null ? requestedModel : mapped;
    }

    private String resolveFromMapping(Map<String, String> mapping, String requestedModel) {
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

    public String stringValue(Map<String, Object> map, String key) {
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

    public String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public String normalizeServiceTier(String rawTier) {
        return fastPolicyService.normalizeServiceTier(rawTier);
    }

    public String normalizeOpenAiReasoningEffort(String rawEffort) {
        if (rawEffort == null) {
            return "";
        }
        String normalized = rawEffort.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = normalized.replace("-", "").replace("_", "").replace(" ", "");
        return switch (normalized) {
            case "none", "minimal" -> "none";
            case "low", "medium", "high" -> normalized;
            case "xhigh", "extrahigh" -> "xhigh";
            default -> "";
        };
    }

    public String normalizeOpenAiModelForUpstream(AdminAccountResponse account, String model) {
        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedModel.isEmpty()) {
            return normalizeCodexModel("");
        }
        if (isOauthLikeType(normalize(account == null ? null : account.type()))) {
            return normalizeCodexModel(normalizedModel);
        }
        return normalizedModel;
    }

    public boolean supportsVerbosity(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("gpt-")) {
            return true;
        }
        String version = normalized.substring("gpt-".length());
        int dot = version.indexOf('.');
        if (dot < 0) {
            return true;
        }
        int dash = version.indexOf('-');
        String majorToken = dash >= 0 ? version.substring(0, dash) : version;
        String[] parts = majorToken.split("\\.");
        if (parts.length < 2) {
            return true;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major > 5) {
                return true;
            }
            if (major < 5) {
                return false;
            }
            return minor >= 3;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private String normalizeCodexModel(String model) {
        String normalized = model == null ? "" : model.trim();
        if (normalized.isEmpty()) {
            return "gpt-5.4";
        }
        String known = normalizeKnownCodexModel(normalized);
        return known.isEmpty() ? normalized : known;
    }

    private String normalizeKnownCodexModel(String model) {
        String normalized = model == null ? "" : model.trim();
        if (normalized.isEmpty() || isOpenAiImageGenerationModel(normalized)) {
            return normalized;
        }
        String modelId = lastOpenAiModelSegment(normalized);
        String canonicalized = canonicalizeOpenAiModelAliasSpelling(modelId);
        if (!canonicalized.isEmpty()) {
            modelId = canonicalized;
        }
        String knownOpenAiCodexModel = normalizeKnownOpenAiCodexModel(modelId);
        if (!knownOpenAiCodexModel.isEmpty()) {
            return knownOpenAiCodexModel;
        }
        String lookupKey = codexModelLookupKey(modelId);
        if (lookupKey.isEmpty()) {
            return "";
        }
        String mapped = getNormalizedCodexModel(lookupKey);
        if (!mapped.isEmpty()) {
            return mapped;
        }
        for (CodexVersionPrefix prefix : CODEx_VERSION_MODEL_PREFIXES) {
            if (lookupKey.equals(prefix.prefix())) {
                return prefix.target();
            }
            if (lookupKey.startsWith(prefix.prefix() + "-")) {
                String suffix = lookupKey.substring(prefix.prefix().length() + 1);
                if (isKnownCodexModelSuffix(suffix)) {
                    return prefix.target();
                }
            }
        }
        return "";
    }

    private String lastOpenAiModelSegment(String model) {
        String normalized = model == null ? "" : model.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1).trim();
        }
        return normalized;
    }

    private String canonicalizeOpenAiModelAliasSpelling(String model) {
        String normalized = normalize(lastOpenAiModelSegment(model)).replace("_", "-");
        normalized = String.join("-", normalized.trim().split("\\s+"));
        while (normalized.contains("--")) {
            normalized = normalized.replace("--", "-");
        }
        if (normalized.startsWith("gpt5")) {
            normalized = "gpt-5" + normalized.substring("gpt5".length());
        }
        if (!normalized.startsWith("gpt-") && !normalized.contains("codex")) {
            return "";
        }
        normalized = normalized
                .replace("gpt-5.4mini", "gpt-5.4-mini")
                .replace("gpt-5.4nano", "gpt-5.4-nano")
                .replace("gpt-5.3-codexspark", "gpt-5.3-codex-spark")
                .replace("gpt-5.3codexspark", "gpt-5.3-codex-spark")
                .replace("gpt-5.3codex", "gpt-5.3-codex");
        return normalized;
    }

    private String normalizeKnownOpenAiCodexModel(String model) {
        String normalized = canonicalizeOpenAiModelAliasSpelling(model);
        if (normalized.isEmpty()) {
            return "";
        }
        String mapped = getNormalizedCodexModel(normalized);
        if (!mapped.isEmpty()) {
            return mapped;
        }
        if (normalized.endsWith("-openai-compact")) {
            mapped = getNormalizedCodexModel(normalized.substring(0, normalized.length() - "-openai-compact".length()));
            if (!mapped.isEmpty()) {
                return mapped;
            }
        }
        if (normalized.contains("gpt-5.5")) {
            return "gpt-5.5";
        }
        if (normalized.contains("gpt-5.4-mini")) {
            return "gpt-5.4-mini";
        }
        if (normalized.contains("gpt-5.4-nano")) {
            return "gpt-5.4-nano";
        }
        if (normalized.contains("gpt-5.4")) {
            return "gpt-5.4";
        }
        if (normalized.contains("gpt-5.2")) {
            return "gpt-5.2";
        }
        if (normalized.contains("gpt-5.3-codex-spark")) {
            return "gpt-5.3-codex-spark";
        }
        if (normalized.contains("gpt-5.3-codex")) {
            return "gpt-5.3-codex";
        }
        if (normalized.contains("gpt-5.3")) {
            return "gpt-5.3-codex";
        }
        if (normalized.contains("codex")) {
            return "gpt-5.3-codex";
        }
        if (normalized.contains("gpt-5")) {
            return "gpt-5.4";
        }
        return "";
    }

    private String codexModelLookupKey(String modelId) {
        String normalized = lastOpenAiModelSegment(modelId);
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = normalized.toLowerCase(Locale.ROOT).trim();
        return String.join("-", normalized.split("\\s+"));
    }

    private String getNormalizedCodexModel(String modelId) {
        return CODEX_MODEL_MAP.getOrDefault(codexModelLookupKey(modelId), "");
    }

    private boolean isKnownCodexModelSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return false;
        }
        return switch (suffix) {
            case "none", "minimal", "low", "medium", "high", "xhigh" -> true;
            default -> isCodexDateSuffix(suffix);
        };
    }

    private boolean isCodexDateSuffix(String suffix) {
        String[] parts = suffix.split("-");
        if (parts.length != 3 || parts[0].length() != 4 || parts[1].length() != 2 || parts[2].length() != 2) {
            return false;
        }
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean looksLikeCodexModel(String model) {
        return normalize(model).contains("codex");
    }

    private boolean isOfficialCodexClient(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return matchesOfficialCodexHeader(request.getHeader("User-Agent"))
                || matchesOfficialCodexHeader(request.getHeader("Originator"));
    }

    private boolean matchesOfficialCodexHeader(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("codex_")
                || normalized.startsWith("codex ")
                || normalized.contains("codex_vscode/")
                || normalized.contains("codex_cli_rs/")
                || normalized.contains("codex_app/")
                || normalized.contains("codex_chatgpt_desktop/")
                || normalized.contains("codex_atlas/")
                || normalized.contains("codex_exec/")
                || normalized.contains("codex_sdk_ts/")
                || normalized.contains("codex desktop");
    }

    private void enforceImageGenerationPermission(
            ObjectNode requestBody,
            String requestedModel,
            GatewayRuntimeContext runtimeContext
    ) {
        if (!isImageGenerationIntent(requestBody, requestedModel)) {
            return;
        }
        if (runtimeContext == null
                || runtimeContext.apiKey() == null
                || runtimeContext.apiKey().group() == null
                || !runtimeContext.apiKey().group().allowImageGeneration()) {
            throw new OpenAiApiErrorException(403, "permission_error", "Image generation is not enabled for this group");
        }
    }

    private boolean isImageGenerationIntent(ObjectNode requestBody, String requestedModel) {
        if (isOpenAiImageGenerationModel(requestedModel)) {
            return true;
        }
        if (requestBody == null) {
            return false;
        }
        if (isOpenAiImageGenerationModel(text(requestBody.get("model")))) {
            return true;
        }
        if (hasImageGenerationTool(requestBody.get("tools"))) {
            return true;
        }
        return toolChoiceSelectsImageGeneration(requestBody.get("tool_choice"));
    }

    private boolean hasImageGenerationTool(JsonNode toolsNode) {
        if (!(toolsNode instanceof ArrayNode tools)) {
            return false;
        }
        for (JsonNode item : tools) {
            if ("image_generation".equals(text(item.get("type")))) {
                return true;
            }
        }
        return false;
    }

    private boolean toolChoiceSelectsImageGeneration(JsonNode toolChoiceNode) {
        if (toolChoiceNode == null || toolChoiceNode.isNull()) {
            return false;
        }
        if (toolChoiceNode.isTextual()) {
            return "image_generation".equals(text(toolChoiceNode));
        }
        return "image_generation".equals(text(toolChoiceNode.get("type")))
                || "image_generation".equals(text(toolChoiceNode.path("tool").get("type")))
                || "image_generation".equals(text(toolChoiceNode.path("function").get("name")));
    }

    private boolean isOpenAiImageGenerationModel(String model) {
        String normalized = normalize(model);
        return normalized.startsWith("gpt-image")
                || normalized.contains("image");
    }

    private void sanitizeEmptyBase64InputImages(ObjectNode requestBody) {
        if (requestBody == null) {
            return;
        }
        JsonNode inputNode = requestBody.get("input");
        if (!(inputNode instanceof ArrayNode inputItems)) {
            return;
        }
        List<JsonNode> retainedItems = new ArrayList<>();
        boolean changed = false;
        for (JsonNode itemNode : inputItems) {
            if (!(itemNode instanceof ObjectNode itemObject)) {
                retainedItems.add(itemNode);
                continue;
            }
            if (shouldDropEmptyBase64InputImagePart(itemObject)) {
                changed = true;
                continue;
            }
            JsonNode contentNode = itemObject.get("content");
            if (!(contentNode instanceof ArrayNode contentItems)) {
                retainedItems.add(itemObject);
                continue;
            }
            ArrayNode retainedContent = objectMapper.createArrayNode();
            boolean itemChanged = false;
            for (JsonNode partNode : contentItems) {
                if (shouldDropEmptyBase64InputImagePart(partNode)) {
                    changed = true;
                    itemChanged = true;
                    continue;
                }
                retainedContent.add(partNode);
            }
            if (itemChanged) {
                if (retainedContent.isEmpty()) {
                    continue;
                }
                itemObject.set("content", retainedContent);
            }
            retainedItems.add(itemObject);
        }
        if (!changed) {
            return;
        }
        ArrayNode normalizedInput = objectMapper.createArrayNode();
        retainedItems.forEach(normalizedInput::add);
        requestBody.set("input", normalizedInput);
    }

    private boolean shouldDropEmptyBase64InputImagePart(JsonNode node) {
        if (!(node instanceof ObjectNode objectNode)) {
            return false;
        }
        if (!"input_image".equals(text(objectNode.get("type")))) {
            return false;
        }
        return isEmptyBase64DataUri(text(objectNode.get("image_url")));
    }

    private boolean isEmptyBase64DataUri(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim();
        if (!normalized.startsWith("data:")) {
            return false;
        }
        int semicolon = normalized.indexOf(';');
        if (semicolon < 0) {
            return false;
        }
        String rest = normalized.substring(semicolon + 1);
        if (!rest.startsWith("base64,")) {
            return false;
        }
        return rest.substring("base64,".length()).trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private record ResponsesPayload(byte[] body, boolean stream) {
    }

    private record CodexVersionPrefix(String prefix, String target) {
    }

    private static final List<CodexVersionPrefix> CODEx_VERSION_MODEL_PREFIXES = List.of(
            new CodexVersionPrefix("gpt-5.3-codex-spark", "gpt-5.3-codex-spark"),
            new CodexVersionPrefix("gpt-5.3-codex", "gpt-5.3-codex"),
            new CodexVersionPrefix("gpt-5.4-mini", "gpt-5.4-mini"),
            new CodexVersionPrefix("gpt-5.4-nano", "gpt-5.4-nano"),
            new CodexVersionPrefix("gpt-5.5", "gpt-5.5"),
            new CodexVersionPrefix("gpt-5.4", "gpt-5.4"),
            new CodexVersionPrefix("gpt-5.2", "gpt-5.2")
    );

    private static final Map<String, String> CODEX_MODEL_MAP = Map.ofEntries(
            Map.entry("gpt-5.5", "gpt-5.5"),
            Map.entry("gpt-5.4", "gpt-5.4"),
            Map.entry("gpt-5.4-mini", "gpt-5.4-mini"),
            Map.entry("gpt-5.4-none", "gpt-5.4"),
            Map.entry("gpt-5.4-low", "gpt-5.4"),
            Map.entry("gpt-5.4-medium", "gpt-5.4"),
            Map.entry("gpt-5.4-high", "gpt-5.4"),
            Map.entry("gpt-5.4-xhigh", "gpt-5.4"),
            Map.entry("gpt-5.4-chat-latest", "gpt-5.4"),
            Map.entry("gpt-5.3", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-none", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-low", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-medium", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-high", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-xhigh", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-spark", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-low", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-medium", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-high", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-xhigh", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-low", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-medium", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-high", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-xhigh", "gpt-5.3-codex"),
            Map.entry("gpt-5.2", "gpt-5.2"),
            Map.entry("gpt-5.2-none", "gpt-5.2"),
            Map.entry("gpt-5.2-low", "gpt-5.2"),
            Map.entry("gpt-5.2-medium", "gpt-5.2"),
            Map.entry("gpt-5.2-high", "gpt-5.2"),
            Map.entry("gpt-5.2-xhigh", "gpt-5.2"),
            Map.entry("gpt-5", "gpt-5.4"),
            Map.entry("gpt-5-mini", "gpt-5.4"),
            Map.entry("gpt-5-nano", "gpt-5.4"),
            Map.entry("gpt-5.1", "gpt-5.4"),
            Map.entry("gpt-5.1-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.1-codex-max", "gpt-5.3-codex"),
            Map.entry("gpt-5.1-codex-mini", "gpt-5.3-codex"),
            Map.entry("gpt-5.2-codex", "gpt-5.2"),
            Map.entry("codex-mini-latest", "gpt-5.3-codex"),
            Map.entry("gpt-5-codex", "gpt-5.3-codex")
    );

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
