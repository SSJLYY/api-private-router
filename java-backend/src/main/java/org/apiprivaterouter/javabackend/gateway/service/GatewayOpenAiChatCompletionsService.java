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
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class GatewayOpenAiChatCompletionsService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final Set<String> RAW_REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept",
            "accept-language",
            "content-type",
            "openai-organization",
            "openai-project",
            "user-agent"
    );
    private static final Set<String> COMPAT_REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept-language",
            "openai-beta",
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
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminAccountRepository accountRepository;
    private final AdminProxyRepository proxyRepository;
    private final GatewayOpenAiResponsesService responsesService;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public GatewayOpenAiChatCompletionsService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            GatewayOpenAiResponsesService responsesService,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.responsesService = responsesService;
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
        String type = normalize(account.type());
        if (isOauthLikeType(type)) {
            forwardCompat(account, request, response, body);
            return;
        }
        if ("apikey".equals(type)) {
            if (shouldUseCompatForApiKey(account, body)) {
                forwardCompat(account, request, response, body);
                return;
            }
            forwardRaw(account, request, response, body);
            return;
        }
        throw new OpenAiApiErrorException(501, "unsupported_error", "OpenAI chat completions forwarding is not supported for this account type yet");
    }

    private boolean shouldUseCompatForApiKey(AdminAccountResponse account, byte[] body) {
        if (account == null || !"apikey".equals(normalize(account.type()))) {
            return false;
        }
        if (!supportsResponsesApi(account.extra())) {
            return false;
        }
        if (body == null || body.length == 0) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                return false;
            }
            if (!objectNode.has("messages")) {
                return false;
            }
            return !objectNode.has("input");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean supportsResponsesApi(Map<String, Object> extra) {
        if (extra == null) {
            return true;
        }
        Object raw = extra.get("openai_responses_supported");
        if (raw instanceof Boolean supported) {
            return supported;
        }
        return raw == null;
    }

    private void forwardRaw(AdminAccountResponse account, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        ChatPayload payload = prepareRawPayload(body, account);
        HttpRequest upstreamRequest = buildRawRequest(account, request, payload.body(), payload.stream());
        HttpResponse<InputStream> upstream = send(account, upstreamRequest);
        throwIfFailoverRequired(upstream);
        writePassthroughResponse(response, upstream, payload.stream());
    }

    private void forwardCompat(AdminAccountResponse account, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        CompatChatPayload payload = prepareCompatPayload(body, account);
        HttpRequest upstreamRequest = buildCompatRequest(account, request, payload.responsesBody());
        HttpResponse<InputStream> upstream = send(account, upstreamRequest);
        throwIfFailoverRequired(upstream);
        if (upstream.statusCode() >= 400) {
            throw translateOpenAiError(upstream);
        }
        if (payload.stream()) {
            streamCompatResponse(response, upstream, payload.clientModel(), payload.includeUsage());
            return;
        }
        writeCompatBufferedResponse(response, upstream, payload.clientModel());
    }

    private ChatPayload prepareRawPayload(byte[] body, AdminAccountResponse account) {
        if (body == null || body.length == 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            String requestedModel = requireTextField(objectNode, "model", "model is required");
            String mappedModel = resolveMappedModel(account, requestedModel);
            if (!mappedModel.equals(requestedModel)) {
                objectNode.put("model", mappedModel);
            }
            boolean stream = objectNode.path("stream").asBoolean(false);
            if (stream) {
                ObjectNode streamOptions = objectNode.path("stream_options") instanceof ObjectNode existing
                        ? existing
                        : objectMapper.createObjectNode();
                streamOptions.put("include_usage", true);
                objectNode.set("stream_options", streamOptions);
            }
            return new ChatPayload(objectMapper.writeValueAsBytes(objectNode), stream);
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private CompatChatPayload prepareCompatPayload(byte[] body, AdminAccountResponse account) {
        if (body == null || body.length == 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode chatRequest)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            String clientModel = requireTextField(chatRequest, "model", "model is required");
            boolean stream = chatRequest.path("stream").asBoolean(false);
            boolean includeUsage = stream && chatRequest.path("stream_options").path("include_usage").asBoolean(false);

            ObjectNode responsesRequest = objectMapper.createObjectNode();
            responsesRequest.put("model", resolveMappedModel(account, clientModel));
            responsesRequest.put("stream", true);
            responsesRequest.put("store", false);
            responsesRequest.put("instructions", extractInstructions(chatRequest));

            Integer maxOutputTokens = resolveMaxOutputTokens(chatRequest);
            if (maxOutputTokens != null) {
                responsesRequest.put("max_output_tokens", Math.max(1, maxOutputTokens));
            }
            copyIfPresent(chatRequest, responsesRequest, "temperature");
            copyIfPresent(chatRequest, responsesRequest, "top_p");
            copyServiceTier(chatRequest, responsesRequest);
            copyReasoning(chatRequest, responsesRequest);
            copyTools(chatRequest, responsesRequest);
            copyToolChoice(chatRequest, responsesRequest);
            ArrayNode input = convertMessages(chatRequest.path("messages"));
            responsesRequest.set("input", input);

            return new CompatChatPayload(
                    objectMapper.writeValueAsBytes(responsesRequest),
                    clientModel,
                    stream,
                    includeUsage
            );
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private HttpRequest buildRawRequest(AdminAccountResponse account, HttpServletRequest inbound, byte[] body, boolean stream) {
        String apiKey = stringValue(account.credentials(), "api_key");
        if (apiKey == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No upstream credentials available");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(buildChatCompletionsUrl(account)))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json");

        copyAllowedHeaders(inbound, builder, RAW_REQUEST_HEADER_ALLOWLIST);
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Accept", stream ? "text/event-stream" : "application/json");
        applyCustomUserAgent(builder, account);
        return builder.build();
    }

    private HttpRequest buildCompatRequest(AdminAccountResponse account, HttpServletRequest inbound, byte[] body) {
        String authToken = responsesService.stringValue(account.credentials(), "access_token");
        if (authToken == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No upstream credentials available");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(responsesService.buildResponsesUrl(account, "")))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");

        copyAllowedHeaders(inbound, builder, COMPAT_REQUEST_HEADER_ALLOWLIST);
        builder.setHeader("Authorization", "Bearer " + authToken);
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Accept", "text/event-stream");
        builder.setHeader("Host", "chatgpt.com");
        String chatgptAccountId = responsesService.stringValue(account.credentials(), "chatgpt_account_id");
        if (chatgptAccountId != null) {
            builder.setHeader("chatgpt-account-id", chatgptAccountId);
        }
        builder.setHeader("Originator", "codex_cli_rs");
        applyCustomUserAgent(builder, account);
        return builder.build();
    }

    private void writePassthroughResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, boolean requestedStream) {
        response.setStatus(upstream.statusCode());
        copyResponseHeaders(upstream, response);
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

    private void streamCompatResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, String clientModel, boolean includeUsage) {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        copyResponseHeaders(upstream, response);
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            ServletOutputStream output = response.getOutputStream();
            ChatChunkState state = new ChatChunkState(clientModel, includeUsage);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode event = objectMapper.readTree(payload);
                String type = text(event.get("type"));
                if ("response.created".equals(type)) {
                    JsonNode responseNode = event.get("response");
                    if (responseNode != null && responseNode.isObject()) {
                        String responseId = text(responseNode.get("id"));
                        if (!responseId.isEmpty()) {
                            state.id = responseId;
                        }
                    }
                    if (!state.sentRole) {
                        writeCompatChunk(output, chunkWithDelta(state, deltaRoleOnly()));
                        state.sentRole = true;
                    }
                    continue;
                }
                if ("response.output_text.delta".equals(type)) {
                    String delta = text(event.get("delta"));
                    if (!delta.isEmpty()) {
                        writeCompatChunk(output, chunkWithDelta(state, deltaWithContent(delta)));
                    }
                    continue;
                }
                if ("response.reasoning_summary_text.delta".equals(type)) {
                    String delta = text(event.get("delta"));
                    if (!delta.isEmpty()) {
                        writeCompatChunk(output, chunkWithDelta(state, deltaWithReasoning(delta)));
                    }
                    continue;
                }
                if ("response.output_item.added".equals(type)) {
                    JsonNode item = event.get("item");
                    if (item != null && "function_call".equals(text(item.get("type")))) {
                        int toolIndex = state.nextToolIndex++;
                        state.outputIndexToToolIndex.put(event.path("output_index").asInt(-1), toolIndex);
                        state.sawToolCall = true;
                        writeCompatChunk(output, chunkWithDelta(state, deltaWithToolStart(toolIndex, item)));
                    }
                    continue;
                }
                if ("response.function_call_arguments.delta".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(-1);
                    Integer toolIndex = state.outputIndexToToolIndex.get(outputIndex);
                    String delta = text(event.get("delta"));
                    if (toolIndex != null && !delta.isEmpty()) {
                        writeCompatChunk(output, chunkWithDelta(state, deltaWithToolArguments(toolIndex, delta)));
                    }
                    continue;
                }
                if (isTerminalEvent(type)) {
                    JsonNode responseNode = event.get("response");
                    if (responseNode instanceof ObjectNode objectNode) {
                        state.usage = extractUsage(objectNode.path("usage"));
                        state.finishReason = extractFinishReason(objectNode, state.sawToolCall);
                    } else if (state.sawToolCall) {
                        state.finishReason = "tool_calls";
                    }
                    writeCompatChunk(output, finishChunk(state));
                    if (includeUsage && state.usage != null) {
                        writeCompatChunk(output, usageChunk(state));
                    }
                    writeDone(output);
                    response.flushBuffer();
                    return;
                }
            }
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "api_error", "Failed to stream chat completions response");
        }
        throw new OpenAiApiErrorException(502, "api_error", "Upstream stream ended without a terminal response event");
    }

    private void writeCompatBufferedResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, String clientModel) {
        ObjectNode terminal = readCompatTerminalResponse(upstream);
        ObjectNode compat = toChatCompletionsResponse(terminal, clientModel);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(writeJsonString(compat));
            response.flushBuffer();
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(500, "api_error", "Failed to write chat completions response");
        }
    }

    private ObjectNode readCompatTerminalResponse(HttpResponse<InputStream> upstream) {
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            BufferedCompatAccumulator accumulator = new BufferedCompatAccumulator();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode event = objectMapper.readTree(payload);
                accumulator.process(event);
                String type = text(event.get("type"));
                if (isTerminalEvent(type) && event.get("response") instanceof ObjectNode responseNode) {
                    ObjectNode terminal = responseNode.deepCopy();
                    accumulator.supplement(terminal);
                    return terminal;
                }
            }
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "api_error", "Failed to read OpenAI upstream response");
        }
        throw new OpenAiApiErrorException(502, "api_error", "Upstream stream ended without a terminal response event");
    }

    private ObjectNode toChatCompletionsResponse(ObjectNode responseNode, String clientModel) {
        ObjectNode result = objectMapper.createObjectNode();
        String id = text(responseNode.get("id"));
        result.put("id", id.isEmpty() ? generateChatCompletionId() : id);
        result.put("object", "chat.completion");
        result.put("created", Instant.now().getEpochSecond());
        result.put("model", clientModel);

        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", buildAssistantMessage(responseNode));
        choice.put("finish_reason", extractFinishReason(responseNode, hasToolCalls(responseNode)));
        ArrayNode choices = objectMapper.createArrayNode();
        choices.add(choice);
        result.set("choices", choices);

        ObjectNode usage = extractUsage(responseNode.path("usage"));
        if (usage != null) {
            result.set("usage", usage);
        }
        return result;
    }

    private ObjectNode buildAssistantMessage(ObjectNode responseNode) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        ArrayNode toolCalls = objectMapper.createArrayNode();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        JsonNode output = responseNode.get("output");
        if (output instanceof ArrayNode items) {
            for (JsonNode item : items) {
                String type = text(item.get("type"));
                if ("message".equals(type)) {
                    JsonNode contentNode = item.get("content");
                    if (contentNode instanceof ArrayNode parts) {
                        for (JsonNode part : parts) {
                            if ("output_text".equals(text(part.get("type")))) {
                                content.append(text(part.get("text")));
                            }
                        }
                    }
                    continue;
                }
                if ("function_call".equals(type)) {
                    ObjectNode toolCall = objectMapper.createObjectNode();
                    toolCall.put("id", text(item.get("call_id")));
                    toolCall.put("type", "function");
                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("name", text(item.get("name")));
                    function.put("arguments", text(item.get("arguments")));
                    toolCall.set("function", function);
                    toolCalls.add(toolCall);
                    continue;
                }
                if ("reasoning".equals(type)) {
                    JsonNode summary = item.get("summary");
                    if (summary instanceof ArrayNode parts) {
                        for (JsonNode part : parts) {
                            if ("summary_text".equals(text(part.get("type")))) {
                                reasoning.append(text(part.get("text")));
                            }
                        }
                    }
                }
            }
        }

        message.put("content", content.toString());
        if (reasoning.length() > 0) {
            message.put("reasoning_content", reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }
        return message;
    }

    private boolean hasToolCalls(ObjectNode responseNode) {
        JsonNode output = responseNode.get("output");
        if (!(output instanceof ArrayNode items)) {
            return false;
        }
        for (JsonNode item : items) {
            if ("function_call".equals(text(item.get("type")))) {
                return true;
            }
        }
        return false;
    }

    private String extractFinishReason(ObjectNode responseNode, boolean sawToolCall) {
        if (responseNode == null) {
            return sawToolCall ? "tool_calls" : "stop";
        }
        String status = text(responseNode.get("status"));
        if ("incomplete".equals(status)) {
            JsonNode details = responseNode.get("incomplete_details");
            if (details != null && "max_output_tokens".equals(text(details.get("reason")))) {
                return "length";
            }
            return "stop";
        }
        if ("completed".equals(status) && sawToolCall) {
            return "tool_calls";
        }
        return "stop";
    }

    private ObjectNode extractUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        ObjectNode usage = objectMapper.createObjectNode();
        int promptTokens = usageNode.path("input_tokens").asInt(0);
        int completionTokens = usageNode.path("output_tokens").asInt(0);
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        int cachedTokens = usageNode.path("input_tokens_details").path("cached_tokens").asInt(0);
        if (cachedTokens > 0) {
            ObjectNode promptDetails = objectMapper.createObjectNode();
            promptDetails.put("cached_tokens", cachedTokens);
            usage.set("prompt_tokens_details", promptDetails);
        }
        return usage;
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

    private String buildChatCompletionsUrl(AdminAccountResponse account) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_OPENAI_BASE_URL;
        }
        baseUrl = upstreamUrlGuard.normalizeAccountBaseUrl(account.platform(), account.type(), baseUrl, DEFAULT_OPENAI_BASE_URL);
        String normalized = trimTrailingSlash(baseUrl);
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private ArrayNode convertMessages(JsonNode messagesNode) {
        if (!(messagesNode instanceof ArrayNode messages) || messages.isEmpty()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "messages is required");
        }
        ArrayNode input = objectMapper.createArrayNode();
        for (JsonNode item : messages) {
            if (!(item instanceof ObjectNode messageNode)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "messages must contain objects");
            }
            String role = normalizeRole(text(messageNode.get("role")));
            if ("tool".equals(role)) {
                input.add(convertToolMessage(messageNode));
                continue;
            }
            if ("assistant".equals(role)) {
                appendAssistantItems(input, messageNode);
                continue;
            }
            input.add(convertStandardMessage(role, messageNode.get("content")));
        }
        return input;
    }

    private ObjectNode convertToolMessage(ObjectNode messageNode) {
        String callId = text(messageNode.get("tool_call_id"));
        if (callId.isEmpty()) {
            callId = text(messageNode.get("name"));
        }
        if (callId.isEmpty()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "tool message requires tool_call_id");
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", flattenContentToString(messageNode.get("content")));
        return item;
    }

    private void appendAssistantItems(ArrayNode input, ObjectNode messageNode) {
        String contentText = flattenAssistantContent(messageNode.get("content"));
        if (!contentText.isBlank()) {
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("type", "message");
            assistant.put("role", "assistant");
            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode textPart = objectMapper.createObjectNode();
            textPart.put("type", "output_text");
            textPart.put("text", contentText);
            parts.add(textPart);
            assistant.set("content", parts);
            input.add(assistant);
        }
        JsonNode toolCallsNode = messageNode.get("tool_calls");
        if (toolCallsNode instanceof ArrayNode toolCalls) {
            for (JsonNode toolCall : toolCalls) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "function_call");
                item.put("call_id", text(toolCall.get("id")));
                JsonNode functionNode = toolCall.get("function");
                item.put("name", text(functionNode == null ? null : functionNode.get("name")));
                String arguments = text(functionNode == null ? null : functionNode.get("arguments"));
                item.put("arguments", arguments.isBlank() ? "{}" : arguments);
                input.add(item);
            }
        }
    }

    private ObjectNode convertStandardMessage(String role, JsonNode contentNode) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "message");
        message.put("role", role);
        if (contentNode == null || contentNode.isNull()) {
            message.put("content", "");
            return message;
        }
        if (contentNode.isTextual()) {
            message.put("content", contentNode.asText());
            return message;
        }
        if (!(contentNode instanceof ArrayNode items)) {
            message.put("content", flattenContentToString(contentNode));
            return message;
        }
        ArrayNode parts = objectMapper.createArrayNode();
        for (JsonNode part : items) {
            String type = text(part.get("type"));
            if ("text".equals(type)) {
                String text = text(part.get("text"));
                if (!text.isEmpty()) {
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("type", "input_text");
                    out.put("text", text);
                    parts.add(out);
                }
                continue;
            }
            if ("image_url".equals(type) && part.get("image_url") instanceof ObjectNode imageUrl) {
                String url = text(imageUrl.get("url"));
                if (!url.isEmpty()) {
                    ObjectNode out = objectMapper.createObjectNode();
                    out.put("type", "input_image");
                    out.put("image_url", url);
                    parts.add(out);
                }
            }
        }
        if (parts.isEmpty()) {
            message.put("content", flattenContentToString(contentNode));
        } else {
            message.set("content", parts);
        }
        return message;
    }

    private void copyTools(ObjectNode chatRequest, ObjectNode responsesRequest) {
        JsonNode toolsNode = chatRequest.get("tools");
        if (!(toolsNode instanceof ArrayNode tools) || tools.isEmpty()) {
            return;
        }
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode toolNode : tools) {
            if (!(toolNode instanceof ObjectNode tool)) {
                continue;
            }
            if (!"function".equals(text(tool.get("type")))) {
                continue;
            }
            ObjectNode functionNode = tool.get("function") instanceof ObjectNode fn ? fn : null;
            if (functionNode == null) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function");
            item.put("name", text(functionNode.get("name")));
            if (functionNode.has("description")) {
                item.set("description", functionNode.get("description"));
            }
            if (functionNode.has("parameters")) {
                item.set("parameters", functionNode.get("parameters"));
            }
            if (functionNode.has("strict")) {
                item.set("strict", functionNode.get("strict"));
            }
            out.add(item);
        }
        if (!out.isEmpty()) {
            responsesRequest.set("tools", out);
        }
    }

    private void copyToolChoice(ObjectNode chatRequest, ObjectNode responsesRequest) {
        JsonNode toolChoiceNode = chatRequest.get("tool_choice");
        if (toolChoiceNode == null || toolChoiceNode.isNull()) {
            JsonNode legacyFunctionCall = chatRequest.get("function_call");
            if (legacyFunctionCall == null || legacyFunctionCall.isNull()) {
                return;
            }
            if (legacyFunctionCall.isTextual()) {
                responsesRequest.put("tool_choice", legacyFunctionCall.asText());
                return;
            }
            if (legacyFunctionCall instanceof ObjectNode functionCall) {
                String name = text(functionCall.get("name"));
                if (!name.isEmpty()) {
                    ObjectNode toolChoice = objectMapper.createObjectNode();
                    toolChoice.put("type", "function");
                    toolChoice.put("name", name);
                    responsesRequest.set("tool_choice", toolChoice);
                }
            }
            return;
        }
        responsesRequest.set("tool_choice", toolChoiceNode.deepCopy());
    }

    private void copyReasoning(ObjectNode chatRequest, ObjectNode responsesRequest) {
        String reasoningEffort = text(chatRequest.get("reasoning_effort"));
        if (reasoningEffort.isEmpty()) {
            return;
        }
        ObjectNode reasoning = objectMapper.createObjectNode();
        reasoning.put("effort", reasoningEffort);
        reasoning.put("summary", "auto");
        responsesRequest.set("reasoning", reasoning);
    }

    private void copyServiceTier(ObjectNode chatRequest, ObjectNode responsesRequest) {
        JsonNode serviceTierNode = chatRequest.get("service_tier");
        if (serviceTierNode == null || !serviceTierNode.isTextual()) {
            return;
        }
        String normalizedTier = responsesService.normalizeServiceTier(serviceTierNode.asText());
        if (normalizedTier != null) {
            responsesRequest.put("service_tier", normalizedTier);
        }
    }

    private Integer resolveMaxOutputTokens(ObjectNode chatRequest) {
        JsonNode maxCompletionTokens = chatRequest.get("max_completion_tokens");
        if (maxCompletionTokens != null && maxCompletionTokens.canConvertToInt()) {
            return maxCompletionTokens.asInt();
        }
        JsonNode maxTokens = chatRequest.get("max_tokens");
        if (maxTokens != null && maxTokens.canConvertToInt()) {
            return maxTokens.asInt();
        }
        return null;
    }

    private String extractInstructions(ObjectNode chatRequest) {
        String explicit = text(chatRequest.get("instructions"));
        if (!explicit.isBlank()) {
            return explicit;
        }
        JsonNode messagesNode = chatRequest.get("messages");
        if (!(messagesNode instanceof ArrayNode messages)) {
            return "";
        }
        List<String> systemParts = new ArrayList<>();
        for (JsonNode item : messages) {
            if (!"system".equalsIgnoreCase(text(item.get("role")))) {
                continue;
            }
            String content = flattenContentToString(item.get("content"));
            if (!content.isBlank()) {
                systemParts.add(content);
            }
        }
        return String.join("\n\n", systemParts);
    }

    private String flattenAssistantContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (!(contentNode instanceof ArrayNode items)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : items) {
            String type = text(item.get("type"));
            if ("text".equals(type)) {
                builder.append(text(item.get("text")));
                continue;
            }
            if ("thinking".equals(type) || "reasoning".equals(type)) {
                String thinking = text(item.get("thinking"));
                if (thinking.isEmpty()) {
                    thinking = text(item.get("text"));
                }
                if (!thinking.isEmpty()) {
                    builder.append("<thinking>").append(thinking).append("</thinking>");
                }
            }
        }
        return builder.toString();
    }

    private String flattenContentToString(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (!(contentNode instanceof ArrayNode items)) {
            return contentNode.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : items) {
            String type = text(item.get("type"));
            if ("text".equals(type)) {
                builder.append(text(item.get("text")));
                continue;
            }
            if ("image_url".equals(type) && item.get("image_url") instanceof ObjectNode imageUrl) {
                builder.append(text(imageUrl.get("url")));
            }
        }
        return builder.toString();
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null) {
            target.set(fieldName, value);
        }
    }

    private void copyAllowedHeaders(HttpServletRequest inbound, HttpRequest.Builder builder, Set<String> allowlist) {
        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!allowlist.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
    }

    private void applyCustomUserAgent(HttpRequest.Builder builder, AdminAccountResponse account) {
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
            return;
        }
        if (isOauthLikeType(account.type())) {
            builder.setHeader("User-Agent", "codex_cli_rs/0.125.0");
        }
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private void copyResponseHeaders(HttpResponse<InputStream> upstream, HttpServletResponse response) {
        upstream.headers().map().forEach((name, values) -> {
            if (name == null || RESPONSE_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }

    private OpenAiApiErrorException translateOpenAiError(HttpResponse<InputStream> upstream) {
        String message = readOpenAiErrorMessage(upstream);
        if (upstream.statusCode() >= 500) {
            return new OpenAiApiErrorException(502, "upstream_error", message);
        }
        if (upstream.statusCode() == 401 || upstream.statusCode() == 403) {
            return new OpenAiApiErrorException(upstream.statusCode(), "permission_error", message);
        }
        return new OpenAiApiErrorException(upstream.statusCode(), "invalid_request_error", message);
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

    private boolean isTerminalEvent(String type) {
        return "response.completed".equals(type)
                || "response.done".equals(type)
                || "response.incomplete".equals(type)
                || "response.failed".equals(type);
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

    private String requireTextField(ObjectNode objectNode, String fieldName, String message) {
        JsonNode field = objectNode.get(fieldName);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", message);
        }
        return field.asText().trim();
    }

    private String normalizeRole(String role) {
        String normalized = normalize(role);
        if ("developer".equals(normalized)) {
            return "system";
        }
        if (normalized.isEmpty()) {
            return "user";
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
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

    private String writeJsonString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(500, "api_error", "Failed to encode chat completions response");
        }
    }

    private void writeCompatChunk(ServletOutputStream output, ObjectNode chunk) throws IOException {
        output.write(("data: " + writeJsonString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void writeDone(ServletOutputStream output) throws IOException {
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private ObjectNode chunkWithDelta(ChatChunkState state, ObjectNode delta) {
        ObjectNode chunk = baseChunk(state);
        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        choice.putNull("finish_reason");
        choices.add(choice);
        chunk.set("choices", choices);
        return chunk;
    }

    private ObjectNode finishChunk(ChatChunkState state) {
        ObjectNode chunk = baseChunk(state);
        ArrayNode choices = objectMapper.createArrayNode();
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("content", "");
        choice.set("delta", delta);
        choice.put("finish_reason", state.finishReason == null ? "stop" : state.finishReason);
        choices.add(choice);
        chunk.set("choices", choices);
        return chunk;
    }

    private ObjectNode usageChunk(ChatChunkState state) {
        ObjectNode chunk = baseChunk(state);
        chunk.set("choices", objectMapper.createArrayNode());
        chunk.set("usage", state.usage);
        return chunk;
    }

    private ObjectNode baseChunk(ChatChunkState state) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", state.id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", state.created);
        chunk.put("model", state.clientModel);
        return chunk;
    }

    private ObjectNode deltaRoleOnly() {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("role", "assistant");
        return delta;
    }

    private ObjectNode deltaWithContent(String content) {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("content", content);
        return delta;
    }

    private ObjectNode deltaWithReasoning(String reasoning) {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("reasoning_content", reasoning);
        return delta;
    }

    private ObjectNode deltaWithToolStart(int index, JsonNode item) {
        ObjectNode delta = objectMapper.createObjectNode();
        ArrayNode toolCalls = objectMapper.createArrayNode();
        ObjectNode toolCall = objectMapper.createObjectNode();
        toolCall.put("index", index);
        toolCall.put("id", text(item.get("call_id")));
        toolCall.put("type", "function");
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", text(item.get("name")));
        toolCall.set("function", function);
        toolCalls.add(toolCall);
        delta.set("tool_calls", toolCalls);
        return delta;
    }

    private ObjectNode deltaWithToolArguments(int index, String arguments) {
        ObjectNode delta = objectMapper.createObjectNode();
        ArrayNode toolCalls = objectMapper.createArrayNode();
        ObjectNode toolCall = objectMapper.createObjectNode();
        toolCall.put("index", index);
        ObjectNode function = objectMapper.createObjectNode();
        function.put("arguments", arguments);
        toolCall.set("function", function);
        toolCalls.add(toolCall);
        delta.set("tool_calls", toolCalls);
        return delta;
    }

    private String generateChatCompletionId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "chatcmpl-" + HexFormat.of().formatHex(bytes);
    }

    private record ChatPayload(byte[] body, boolean stream) {
    }

    private record CompatChatPayload(byte[] responsesBody, String clientModel, boolean stream, boolean includeUsage) {
    }

    private static final class ChatChunkState {
        private final long created = Instant.now().getEpochSecond();
        private final String clientModel;
        private final boolean includeUsage;
        private String id = "chatcmpl-pending";
        private boolean sentRole;
        private boolean sawToolCall;
        private int nextToolIndex;
        private final Map<Integer, Integer> outputIndexToToolIndex = new LinkedHashMap<>();
        private ObjectNode usage;
        private String finishReason = "stop";

        private ChatChunkState(String clientModel, boolean includeUsage) {
            this.clientModel = clientModel;
            this.includeUsage = includeUsage;
        }
    }

    private final class BufferedCompatAccumulator {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final List<ObjectNode> functionCalls = new ArrayList<>();
        private final Map<Integer, Integer> outputIndexToFunctionIndex = new LinkedHashMap<>();

        private void process(JsonNode event) {
            String type = text(event.get("type"));
            if ("response.output_text.delta".equals(type)) {
                this.text.append(text(event.get("delta")));
                return;
            }
            if ("response.reasoning_summary_text.delta".equals(type)) {
                this.reasoning.append(text(event.get("delta")));
                return;
            }
            if ("response.output_item.added".equals(type)) {
                JsonNode item = event.get("item");
                if (item != null && "function_call".equals(text(item.get("type")))) {
                    ObjectNode functionCall = objectMapper.createObjectNode();
                    functionCall.put("type", "function_call");
                    functionCall.put("call_id", text(item.get("call_id")));
                    functionCall.put("name", text(item.get("name")));
                    functionCall.put("arguments", "");
                    int outputIndex = event.path("output_index").asInt(-1);
                    outputIndexToFunctionIndex.put(outputIndex, functionCalls.size());
                    functionCalls.add(functionCall);
                }
                return;
            }
            if ("response.function_call_arguments.delta".equals(type)) {
                int outputIndex = event.path("output_index").asInt(-1);
                Integer functionIndex = outputIndexToFunctionIndex.get(outputIndex);
                if (functionIndex == null) {
                    return;
                }
                ObjectNode functionCall = functionCalls.get(functionIndex);
                functionCall.put("arguments", functionCall.path("arguments").asText("") + text(event.get("delta")));
            }
        }

        private void supplement(ObjectNode responseNode) {
            JsonNode output = responseNode.get("output");
            if (output instanceof ArrayNode arrayNode && !arrayNode.isEmpty()) {
                return;
            }
            ArrayNode rebuilt = objectMapper.createArrayNode();
            if (reasoning.length() > 0) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "reasoning");
                ArrayNode summary = objectMapper.createArrayNode();
                ObjectNode summaryText = objectMapper.createObjectNode();
                summaryText.put("type", "summary_text");
                summaryText.put("text", reasoning.toString());
                summary.add(summaryText);
                item.set("summary", summary);
                rebuilt.add(item);
            }
            if (text.length() > 0) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "message");
                item.put("role", "assistant");
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode textPart = objectMapper.createObjectNode();
                textPart.put("type", "output_text");
                textPart.put("text", text.toString());
                content.add(textPart);
                item.set("content", content);
                rebuilt.add(item);
            }
            for (ObjectNode functionCall : functionCalls) {
                rebuilt.add(functionCall);
            }
            responseNode.set("output", rebuilt);
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
