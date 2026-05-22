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
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayGroupSummary;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GatewayOpenAiMessagesDispatchService {

    private static final Pattern LEGACY_METADATA_USER_ID_PATTERN = Pattern.compile(
            "^user_([a-fA-F0-9]{64})_account_([a-fA-F0-9-]*)_session_([a-fA-F0-9-]{36})$"
    );
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CONTINUATION_TTL = Duration.ofHours(1);
    private static final String CODEX_CLI_USER_AGENT = "codex_cli_rs/0.125.0";
    private static final String COMPAT_PROMPT_CACHE_KEY_PREFIX = "compat_cc_";
    private static final String ANTHROPIC_FAST_MODE_BETA = "fast-mode-2026-02-01";
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept-language",
            "conversation_id",
            "openai-beta",
            "originator",
            "session_id",
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
    private final GatewayOpenAiResponsesService responsesService;
    private final GatewayOpenAiFastPolicyService fastPolicyService;
    private final ObjectMapper objectMapper;
    private final Map<String, ContinuationBinding> continuationBindings = new ConcurrentHashMap<>();

    public GatewayOpenAiMessagesDispatchService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            GatewayOpenAiResponsesService responsesService,
            GatewayOpenAiFastPolicyService fastPolicyService,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.responsesService = responsesService;
        this.fastPolicyService = fastPolicyService;
        this.objectMapper = objectMapper;
    }

    public boolean canHandle(GatewayRuntimeContext runtimeContext, byte[] body) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            return false;
        }
        if (!"openai".equalsIgnoreCase(runtimeContext.account().platform())) {
            return false;
        }
        return isMinimalHappyPath(body);
    }

    public void forward(GatewayRuntimeContext runtimeContext, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        AdminAccountResponse account = loadAccount(runtimeContext);
        ObjectNode anthropicRequest = parseAnthropicRequest(body);
        validateCompatibilityShape(anthropicRequest);
        String originalModel = requireModel(anthropicRequest);
        applyOpenAiCompatModelNormalization(anthropicRequest, account);
        boolean stream = anthropicRequest.path("stream").asBoolean(false);
        ObjectNode responsesRequest = toResponsesRequest(
                anthropicRequest,
                account,
                runtimeContext.apiKey() == null ? null : runtimeContext.apiKey().group(),
                request,
                stream
        );
        ContinuationContext continuationContext = applyContinuationState(runtimeContext, account, responsesRequest);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest upstreamRequest = buildUpstreamRequest(account, request, responsesRequest, stream);
            HttpResponse<InputStream> upstream = send(account, upstreamRequest);
            throwIfFailoverRequired(upstream);
            if (upstream.statusCode() >= 400) {
                BufferedUpstreamError bufferedError = readBufferedUpstreamError(upstream);
                if (attempt == 0
                        && continuationContext != null
                        && !continuationContext.previousResponseId().isBlank()
                        && maybeRecoverFromPreviousResponseFailure(runtimeContext, account, anthropicRequest, bufferedError, continuationContext)) {
                    responsesRequest = toResponsesRequest(
                            anthropicRequest,
                            account,
                            runtimeContext.apiKey() == null ? null : runtimeContext.apiKey().group(),
                            request,
                            stream
                    );
                    continuationContext = applyContinuationState(runtimeContext, account, responsesRequest);
                    continue;
                }
                throw translateUpstreamError(bufferedError.statusCode(), bufferedError.message());
            }
            if (stream) {
                streamAnthropicResponse(response, upstream, originalModel, runtimeContext, account, continuationContext);
                return;
            }
            writeBufferedAnthropicResponse(response, upstream, originalModel, runtimeContext, account, continuationContext);
            return;
        }
    }

    private AdminAccountResponse loadAccount(GatewayRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            throw new AnthropicApiErrorException(503, "api_error", "No available OpenAI accounts");
        }
        return accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new AnthropicApiErrorException(503, "api_error", "No available OpenAI accounts"));
    }

    private boolean isMinimalHappyPath(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                return false;
            }
            JsonNode tools = objectNode.get("tools");
            if (tools != null && !tools.isNull() && !tools.isArray()) {
                return false;
            }
            JsonNode thinking = objectNode.get("thinking");
            if (thinking != null && !thinking.isNull() && !thinking.isObject()) {
                return false;
            }
            JsonNode stopSequences = objectNode.get("stop_sequences");
            if (stopSequences != null && !stopSequences.isNull()) {
                if (!stopSequences.isArray()) {
                    return false;
                }
                for (JsonNode item : stopSequences) {
                    if (!item.isTextual()) {
                        return false;
                    }
                }
            }
            JsonNode system = objectNode.get("system");
            if (system != null && !system.isNull() && !system.isTextual() && !system.isArray()) {
                return false;
            }
            JsonNode messages = objectNode.get("messages");
            if (!(messages instanceof ArrayNode items) || items.isEmpty()) {
                return false;
            }
            for (JsonNode item : items) {
                if (!(item instanceof ObjectNode messageNode)) {
                    return false;
                }
                JsonNode contentNode = messageNode.get("content");
                if (contentNode == null) {
                    return false;
                }
                if (contentNode.isTextual()) {
                    continue;
                }
                if (!contentNode.isArray()) {
                    return false;
                }
                for (JsonNode block : contentNode) {
                    if (block == null || !block.isObject()) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private ObjectNode parseAnthropicRequest(byte[] body) {
        if (body == null || body.length == 0) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node instanceof ObjectNode objectNode) {
                return objectNode.deepCopy();
            }
        } catch (Exception ignored) {
        }
        throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to parse request body");
    }

    private String requireModel(ObjectNode anthropicRequest) {
        String model = text(anthropicRequest.get("model"));
        if (model.isEmpty()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
        }
        return model;
    }

    private ObjectNode toResponsesRequest(
            ObjectNode anthropicRequest,
            AdminAccountResponse account,
            GatewayGroupSummary group,
            HttpServletRequest inbound,
            boolean stream
    ) {
        ObjectNode request = objectMapper.createObjectNode();
        String requestedModel = requireModel(anthropicRequest);
        String mappedModel = resolveDispatchModel(group, account, requestedModel);
        String upstreamModel = responsesService.normalizeOpenAiModelForUpstream(account, mappedModel);
        request.put("model", upstreamModel);
        request.put("stream", stream);
        request.put("store", false);
        request.put("parallel_tool_calls", true);
        ArrayNode include = objectMapper.createArrayNode();
        include.add("reasoning.encrypted_content");
        request.set("include", include);
        ObjectNode textConfig = objectMapper.createObjectNode();
        textConfig.put("verbosity", "medium");
        request.set("text", textConfig);
        request.set("reasoning", buildReasoningConfig(anthropicRequest));

        JsonNode maxTokens = anthropicRequest.get("max_tokens");
        if (maxTokens != null && maxTokens.canConvertToInt()) {
            request.put("max_output_tokens", Math.max(1, maxTokens.asInt()));
        }
        if (anthropicRequest.has("temperature")) {
            request.set("temperature", anthropicRequest.get("temperature"));
        }
        if (anthropicRequest.has("top_p")) {
            request.set("top_p", anthropicRequest.get("top_p"));
        }
        copyStopSequences(anthropicRequest, request);
        if (anthropicRequest.has("prompt_cache_key") && anthropicRequest.get("prompt_cache_key").isTextual()) {
            String promptCacheKey = anthropicRequest.get("prompt_cache_key").asText("").trim();
            if (!promptCacheKey.isEmpty()) {
                request.put("prompt_cache_key", promptCacheKey);
            }
        }
        ensurePromptCacheKeyFromMetadata(anthropicRequest, request);
        ensurePromptCacheKeyForCompat(anthropicRequest, request, upstreamModel);
        if (anthropicRequest.has("tools")) {
            request.set("tools", convertTools(anthropicRequest.get("tools")));
        }
        if (anthropicRequest.has("tool_choice")) {
            JsonNode convertedToolChoice = convertToolChoice(anthropicRequest.get("tool_choice"));
            if (convertedToolChoice != null && !convertedToolChoice.isNull()) {
                request.set("tool_choice", convertedToolChoice);
            }
        }

        ArrayNode input = objectMapper.createArrayNode();
        appendSystemInput(input, anthropicRequest.get("system"));
        appendMessagesInput(input, anthropicRequest.get("messages"));
        request.set("input", input);
        applyAnthropicFastModeAndPolicy(inbound, account, request, upstreamModel);
        return request;
    }

    private void applyAnthropicFastModeAndPolicy(
            HttpServletRequest inbound,
            AdminAccountResponse account,
            ObjectNode request,
            String upstreamModel
    ) {
        if (request == null || fastPolicyService == null) {
            return;
        }
        String anthropicBeta = resolveHeaderOrDefault(inbound, "anthropic-beta", "");
        if (containsBetaToken(anthropicBeta, ANTHROPIC_FAST_MODE_BETA)) {
            request.put("service_tier", "priority");
        }
        String normalizedServiceTier = fastPolicyService.normalizeServiceTier(text(request.get("service_tier")));
        if (normalizedServiceTier == null) {
            return;
        }
        request.put("service_tier", normalizedServiceTier);
        GatewayOpenAiFastPolicyService.FastPolicyApplyResult fastPolicyResult =
                fastPolicyService.applyToRequestBody(account, upstreamModel, request, normalizedServiceTier);
        if (fastPolicyResult.action() == GatewayOpenAiFastPolicyService.Action.BLOCK) {
            throw new AnthropicApiErrorException(403, "permission_error", fastPolicyResult.blockMessage());
        }
        replaceRequestBody(request, fastPolicyResult.payload());
    }

    private void copyStopSequences(ObjectNode anthropicRequest, ObjectNode request) {
        if (anthropicRequest == null || request == null) {
            return;
        }
        JsonNode stopSequences = anthropicRequest.get("stop_sequences");
        if (stopSequences == null || stopSequences.isNull() || !stopSequences.isArray() || stopSequences.isEmpty()) {
            return;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode item : stopSequences) {
            String value = text(item);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        if (!normalized.isEmpty()) {
            request.set("stop", normalized);
        }
    }

    private void ensurePromptCacheKeyFromMetadata(ObjectNode anthropicRequest, ObjectNode request) {
        if (anthropicRequest == null || request == null) {
            return;
        }
        String existing = text(request.get("prompt_cache_key"));
        if (!existing.isEmpty()) {
            return;
        }
        JsonNode metadataNode = anthropicRequest.get("metadata");
        if (!(metadataNode instanceof ObjectNode metadata)) {
            return;
        }
        String userId = text(metadata.get("user_id"));
        ParsedMetadataUserId parsed = parseMetadataUserId(userId);
        if (parsed == null || parsed.sessionId().isBlank()) {
            return;
        }
        String seed = String.join(
                "|",
                "anthropic-metadata",
                parsed.deviceId(),
                parsed.accountUuid(),
                parsed.sessionId()
        );
        request.put("prompt_cache_key", "anthropic-metadata-" + sha256Hex(seed));
    }

    private void ensurePromptCacheKeyForCompat(ObjectNode anthropicRequest, ObjectNode request, String mappedModel) {
        if (anthropicRequest == null || request == null) {
            return;
        }
        if (!text(request.get("prompt_cache_key")).isEmpty()) {
            return;
        }
        if (!shouldAutoInjectPromptCacheKeyForCompat(mappedModel)) {
            return;
        }
        String derived = deriveAnthropicCompatPromptCacheKey(anthropicRequest, mappedModel);
        if (!derived.isBlank()) {
            request.put("prompt_cache_key", derived);
        }
    }

    private String resolveDispatchModel(GatewayGroupSummary group, AdminAccountResponse account, String requestedModel) {
        if (group != null && group.messagesDispatchModelConfig() != null) {
            GatewayGroupSummary.MessagesDispatchModelConfig config = group.messagesDispatchModelConfig();
            if (config.exactModelMappings() != null) {
                String exact = config.exactModelMappings().get(requestedModel);
                String normalizedExact = normalizeDispatchMappedModel(exact);
                if (!normalizedExact.isBlank()) {
                    return normalizedExact;
                }
            }
            String normalized = requestedModel == null ? "" : requestedModel.trim().toLowerCase(Locale.ROOT);
            String opusMappedModel = normalizeDispatchMappedModel(config.opusMappedModel());
            if (normalized.contains("opus") && !opusMappedModel.isBlank()) {
                return opusMappedModel;
            }
            String sonnetMappedModel = normalizeDispatchMappedModel(config.sonnetMappedModel());
            if (normalized.contains("sonnet") && !sonnetMappedModel.isBlank()) {
                return sonnetMappedModel;
            }
            String haikuMappedModel = normalizeDispatchMappedModel(config.haikuMappedModel());
            if (normalized.contains("haiku") && !haikuMappedModel.isBlank()) {
                return haikuMappedModel;
            }
        }
        String defaultMappedModel = normalizeDispatchMappedModel(group == null ? null : group.defaultMappedModel());
        if (!defaultMappedModel.isBlank()) {
            return defaultMappedModel;
        }
        return normalizeDispatchMappedModel(responsesService.resolveMappedModel(account, requestedModel));
    }

    private String normalizeDispatchMappedModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        CompatRequestedModelNormalization compatModel = parseOpenAiCompatRequestedModel(trimmed);
        if (!compatModel.hasReasoningSuffix() || compatModel.normalizedModel().isBlank()) {
            return trimmed;
        }
        return compatModel.normalizedModel();
    }

    private void applyOpenAiCompatModelNormalization(ObjectNode anthropicRequest, AdminAccountResponse account) {
        if (anthropicRequest == null) {
            return;
        }
        String originalModel = text(anthropicRequest.get("model"));
        if (originalModel.isEmpty()) {
            return;
        }
        CompatRequestedModelNormalization compatModel = parseOpenAiCompatRequestedModel(originalModel);
        if (compatModel.hasReasoningSuffix() && !compatModel.normalizedModel().isBlank()) {
            String normalizedModel = compatModel.normalizedModel();
            if (account != null && isOauthLikeType(account.type())) {
                normalizedModel = responsesService.normalizeOpenAiModelForUpstream(account, normalizedModel);
            }
            anthropicRequest.put("model", normalizedModel);
        }
        JsonNode outputConfigNode = anthropicRequest.get("output_config");
        if (outputConfigNode instanceof ObjectNode outputConfig && !text(outputConfig.get("effort")).isEmpty()) {
            return;
        }
        String compatEffort = toClaudeOutputEffort(compatModel.reasoningEffort());
        if (compatEffort.isEmpty()) {
            return;
        }
        ObjectNode outputConfig = outputConfigNode instanceof ObjectNode existing
                ? existing
                : objectMapper.createObjectNode();
        outputConfig.put("effort", compatEffort);
        anthropicRequest.set("output_config", outputConfig);
    }

    private CompatRequestedModelNormalization parseOpenAiCompatRequestedModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return new CompatRequestedModelNormalization("", "", false);
        }
        String modelId = trimmed;
        int slashIndex = modelId.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex + 1 < modelId.length()) {
            modelId = modelId.substring(slashIndex + 1).trim();
        }
        if (!modelId.toLowerCase(Locale.ROOT).startsWith("gpt-")) {
            return new CompatRequestedModelNormalization(trimmed, "", false);
        }
        int separatorIndex = findLastReasoningSeparator(modelId);
        if (separatorIndex <= 0 || separatorIndex >= modelId.length() - 1) {
            return new CompatRequestedModelNormalization(trimmed, "", false);
        }
        String reasoningToken = modelId.substring(separatorIndex + 1).trim()
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        String reasoningEffort = switch (reasoningToken) {
            case "none", "minimal", "low", "medium", "high" -> reasoningToken;
            case "xhigh", "extrahigh" -> "xhigh";
            default -> "";
        };
        if (reasoningEffort.isEmpty()) {
            return new CompatRequestedModelNormalization(trimmed, "", false);
        }
        String normalizedModel = modelId.substring(0, separatorIndex).trim();
        if (normalizedModel.isEmpty()) {
            return new CompatRequestedModelNormalization(trimmed, "", false);
        }
        return new CompatRequestedModelNormalization(normalizedModel, reasoningEffort, true);
    }

    private int findLastReasoningSeparator(String value) {
        int lastDash = value.lastIndexOf('-');
        int lastUnderscore = value.lastIndexOf('_');
        int lastSpace = value.lastIndexOf(' ');
        return Math.max(lastDash, Math.max(lastUnderscore, lastSpace));
    }

    private String toClaudeOutputEffort(String reasoningEffort) {
        return switch (reasoningEffort) {
            case "low", "medium", "high" -> reasoningEffort;
            case "xhigh" -> "max";
            default -> "";
        };
    }

    private boolean containsBetaToken(String header, String token) {
        if (header == null || header.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        String[] parts = header.split(",");
        for (String part : parts) {
            if (token.equals(part == null ? "" : part.trim())) {
                return true;
            }
        }
        return false;
    }

    private void replaceRequestBody(ObjectNode target, ObjectNode source) {
        if (target == null || source == null || target == source) {
            return;
        }
        target.removeAll();
        target.setAll(source);
    }

    private void appendSystemInput(ArrayNode input, JsonNode systemNode) {
        if (systemNode == null || systemNode.isNull()) {
            return;
        }
        ArrayNode content = objectMapper.createArrayNode();
        if (systemNode.isTextual()) {
            String text = systemNode.asText().trim();
            if (!text.isEmpty()) {
                content.add(textPart(text));
            }
        } else if (systemNode.isArray()) {
            for (JsonNode block : systemNode) {
                String blockText = "text".equals(text(block.get("type"))) ? text(block.get("text")) : "";
                if (!blockText.isEmpty()) {
                    content.add(textPart(blockText));
                }
            }
        }
        if (content.isEmpty()) {
            return;
        }
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "message");
        message.put("role", "developer");
        message.set("content", content);
        input.add(message);
    }

    private ObjectNode buildReasoningConfig(ObjectNode anthropicRequest) {
        ObjectNode reasoning = objectMapper.createObjectNode();
        reasoning.put("summary", "auto");
        reasoning.put("effort", resolveReasoningEffort(anthropicRequest));
        return reasoning;
    }

    private String resolveReasoningEffort(ObjectNode anthropicRequest) {
        JsonNode outputConfig = anthropicRequest == null ? null : anthropicRequest.get("output_config");
        String effort = outputConfig instanceof ObjectNode ? text(outputConfig.get("effort")) : "";
        if (effort.isEmpty()) {
            effort = "medium";
        }
        return switch (effort) {
            case "low", "medium", "high" -> effort;
            case "max" -> "xhigh";
            default -> "medium";
        };
    }

    private void appendMessagesInput(ArrayNode input, JsonNode messagesNode) {
        if (!(messagesNode instanceof ArrayNode messages)) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "messages is required");
        }
        for (JsonNode item : messages) {
            if (!(item instanceof ObjectNode messageNode)) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "messages must contain objects");
            }
            String role = normalizeRole(text(messageNode.get("role")));
            ArrayNode content = objectMapper.createArrayNode();
            JsonNode rawContent = messageNode.get("content");
            if (rawContent == null) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "message content is required");
            }
            if (rawContent.isTextual()) {
                String contentText = rawContent.asText();
                if (!contentText.isBlank()) {
                    content.add(role.equals("assistant") ? outputTextPart(contentText) : textPart(contentText));
                }
            } else if (rawContent.isArray()) {
                if ("assistant".equals(role)) {
                    appendAssistantMessageItems(input, rawContent);
                    continue;
                }
                for (JsonNode block : rawContent) {
                    String blockType = text(block.get("type"));
                    if ("text".equals(blockType)) {
                        String blockText = text(block.get("text"));
                        if (!blockText.isEmpty()) {
                            content.add(textPart(blockText));
                        }
                        continue;
                    }
                    if ("image".equals(blockType)) {
                        String imageUrl = anthropicImageToDataUri(block.get("source"));
                        if (!imageUrl.isEmpty()) {
                            content.add(imagePart(imageUrl));
                        }
                        continue;
                    }
                    if ("tool_result".equals(blockType)) {
                        String toolUseId = text(block.get("tool_use_id"));
                        if (toolUseId.isEmpty()) {
                            continue;
                        }
                        List<String> toolResultImages = new ArrayList<>();
                        ObjectNode toolOutput = objectMapper.createObjectNode();
                        toolOutput.put("type", "function_call_output");
                        toolOutput.put("call_id", toolUseId);
                        toolOutput.put("output", extractToolResultOutput(block.get("content"), toolResultImages));
                        input.add(toolOutput);
                        for (String imageUrl : toolResultImages) {
                            if (!imageUrl.isEmpty()) {
                                content.add(imagePart(imageUrl));
                            }
                        }
                        continue;
                    }
                    if (isIgnoredHistoryBlockType(blockType)) {
                        continue;
                    }
                }
            } else {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "message content must be text or array");
            }
            if (!content.isEmpty()) {
                ObjectNode message = objectMapper.createObjectNode();
                message.put("type", "message");
                message.put("role", role);
                message.set("content", content);
                input.add(message);
            }
        }
    }

    private void appendAssistantMessageItems(ArrayNode input, JsonNode rawContent) {
        ArrayNode assistantContent = objectMapper.createArrayNode();
        for (JsonNode block : rawContent) {
            String blockType = text(block.get("type"));
            if ("text".equals(blockType)) {
                String blockText = text(block.get("text"));
                if (!blockText.isEmpty()) {
                    assistantContent.add(outputTextPart(blockText));
                }
                continue;
            }
            if ("tool_use".equals(blockType)) {
                ObjectNode functionCall = objectMapper.createObjectNode();
                functionCall.put("type", "function_call");
                functionCall.put("call_id", text(block.get("id")));
                functionCall.put("name", text(block.get("name")));
                JsonNode toolInput = block.get("input");
                functionCall.put("arguments", toolInput == null || toolInput.isNull() ? "{}" : compactJson(toolInput));
                input.add(functionCall);
                continue;
            }
            if (isIgnoredHistoryBlockType(blockType)) {
                continue;
            }
        }
        if (!assistantContent.isEmpty()) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "message");
            message.put("role", "assistant");
            message.set("content", assistantContent);
            input.add(message);
        }
    }

    private JsonNode convertToolChoice(JsonNode toolChoiceNode) {
        if (toolChoiceNode == null || toolChoiceNode.isNull()) {
            return null;
        }
        if (!toolChoiceNode.isObject()) {
            return toolChoiceNode.deepCopy();
        }
        String type = text(toolChoiceNode.get("type"));
        return switch (type) {
            case "auto" -> objectMapper.getNodeFactory().textNode("auto");
            case "any" -> objectMapper.getNodeFactory().textNode("required");
            case "none" -> objectMapper.getNodeFactory().textNode("none");
            case "tool" -> {
                ObjectNode mapped = objectMapper.createObjectNode();
                mapped.put("type", "function");
                mapped.put("name", text(toolChoiceNode.get("name")));
                yield mapped;
            }
            default -> toolChoiceNode.deepCopy();
        };
    }

    private HttpRequest buildUpstreamRequest(
            AdminAccountResponse account,
            HttpServletRequest inbound,
            ObjectNode responsesRequest,
            boolean stream
    ) {
        String authToken = resolveAuthToken(account);
        if (authToken == null) {
            throw new AnthropicApiErrorException(503, "api_error", "No upstream credentials available");
        }
        byte[] body = writeJsonBytes(responsesRequest);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(responsesService.buildResponsesUrl(account, "")))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");

        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
        builder.setHeader("Authorization", "Bearer " + authToken);
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Accept", "text/event-stream");
        applyOAuthHeaders(builder, account, inbound, responsesRequest);
        String customUserAgent = responsesService.stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
        } else if (isOauthLikeType(account.type())) {
            builder.setHeader("User-Agent", CODEX_CLI_USER_AGENT);
        }
        return builder.build();
    }

    private String resolveAuthToken(AdminAccountResponse account) {
        if (account == null) {
            return null;
        }
        if (isOauthLikeType(account.type())) {
            return responsesService.stringValue(account.credentials(), "access_token");
        }
        return responsesService.stringValue(account.credentials(), "api_key");
    }

    private void applyOAuthHeaders(
            HttpRequest.Builder builder,
            AdminAccountResponse account,
            HttpServletRequest inbound,
            ObjectNode responsesRequest
    ) {
        if (builder == null || account == null || !isOauthLikeType(account.type())) {
            return;
        }
        builder.setHeader("Host", "chatgpt.com");
        String chatgptAccountId = responsesService.stringValue(account.credentials(), "chatgpt_account_id");
        if (chatgptAccountId != null) {
            builder.setHeader("chatgpt-account-id", chatgptAccountId);
        }
        builder.setHeader("Accept", "text/event-stream");
        builder.setHeader("Originator", resolveHeaderOrDefault(inbound, "Originator", "codex_cli_rs"));
        String promptCacheKey = responsesRequest == null ? "" : text(responsesRequest.get("prompt_cache_key"));
        String sessionId = resolveHeaderOrDefault(inbound, "Session_ID", promptCacheKey);
        String conversationId = resolveHeaderOrDefault(inbound, "Conversation_ID", promptCacheKey);
        if (!sessionId.isEmpty()) {
            builder.setHeader("Session_ID", sessionId);
        }
        if (!conversationId.isEmpty()) {
            builder.setHeader("Conversation_ID", conversationId);
        }
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private HttpResponse<InputStream> send(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnthropicApiErrorException(502, "api_error", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "api_error", "Upstream request failed");
        }
    }

    private void writeBufferedAnthropicResponse(
            HttpServletResponse response,
            HttpResponse<InputStream> upstream,
            String originalModel,
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            ContinuationContext continuationContext
    ) {
        ObjectNode terminal = readTerminalResponse(upstream);
        bindContinuationResponse(runtimeContext, account, continuationContext, terminal);
        ObjectNode anthropic = toAnthropicResponse(terminal, originalModel);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(writeJsonString(anthropic));
            response.flushBuffer();
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(500, "api_error", "Failed to write Anthropic response");
        }
    }

    private void streamAnthropicResponse(
            HttpServletResponse response,
            HttpResponse<InputStream> upstream,
            String originalModel,
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            ContinuationContext continuationContext
    ) {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        copyResponseHeaders(upstream, response);
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            ServletOutputStream output = response.getOutputStream();
            StreamState state = new StreamState(originalModel);
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
                    writeSse(output, "message_start", buildMessageStartEvent(event, state));
                    continue;
                }
                if ("response.output_text.delta".equals(type)) {
                    if (!state.contentBlockStarted || !"text".equals(state.currentBlockType)) {
                        closeActiveBlockIfNeeded(output, state);
                        int blockIndex = openContentBlock(state, "text");
                        writeSse(output, "content_block_start", buildTextContentBlockStartEvent(blockIndex));
                    }
                    String delta = text(event.get("delta"));
                    if (!delta.isEmpty()) {
                        writeSse(output, "content_block_delta", buildContentBlockDeltaEvent(state.currentBlockIndex, delta));
                    }
                    continue;
                }
                if ("response.output_item.added".equals(type)) {
                    JsonNode item = event.get("item");
                    String itemType = text(item == null ? null : item.get("type"));
                    if ("function_call".equals(itemType)) {
                        closeActiveBlockIfNeeded(output, state);
                        int blockIndex = openContentBlock(state, "tool_use");
                        writeSse(output, "content_block_start", buildToolUseContentBlockStartEvent(item, blockIndex));
                        state.currentToolCallId = text(item.get("call_id"));
                        state.hasToolCall = true;
                        continue;
                    }
                    if ("reasoning".equals(itemType)) {
                        closeActiveBlockIfNeeded(output, state);
                        int blockIndex = openContentBlock(state, "thinking");
                        writeSse(output, "content_block_start", buildThinkingContentBlockStartEvent(blockIndex));
                        continue;
                    }
                    continue;
                }
                if ("response.function_call_arguments.delta".equals(type) && "tool_use".equals(state.currentBlockType)) {
                    String delta = text(event.get("delta"));
                    if (!delta.isEmpty()) {
                        state.currentToolHadDelta = true;
                        writeSse(output, "content_block_delta", buildInputJsonDeltaEvent(state.currentBlockIndex, delta));
                    }
                    continue;
                }
                if ("response.reasoning_summary_text.delta".equals(type) && "thinking".equals(state.currentBlockType)) {
                    String delta = text(event.get("delta"));
                    if (!delta.isEmpty()) {
                        writeSse(output, "content_block_delta", buildThinkingDeltaEvent(state.currentBlockIndex, delta));
                    }
                    continue;
                }
                if ("response.output_text.done".equals(type) && "text".equals(state.currentBlockType)) {
                    closeActiveBlockIfNeeded(output, state);
                    continue;
                }
                if ("response.function_call_arguments.done".equals(type) && "tool_use".equals(state.currentBlockType)) {
                    String rawArguments = rawTextOrJson(event.get("arguments"));
                    if (!rawArguments.isEmpty() && !state.currentToolHadDelta) {
                        writeSse(output, "content_block_delta", buildInputJsonDeltaEvent(state.currentBlockIndex, rawArguments));
                    }
                    closeActiveBlockIfNeeded(output, state);
                    continue;
                }
                if ("response.reasoning_summary_text.done".equals(type) && "thinking".equals(state.currentBlockType)) {
                    closeActiveBlockIfNeeded(output, state);
                    continue;
                }
                if ("response.output_item.done".equals(type)) {
                    JsonNode item = event.get("item");
                    String itemType = text(item == null ? null : item.get("type"));
                    if ("web_search_call".equals(itemType) && "completed".equals(text(item == null ? null : item.get("status")))) {
                        closeActiveBlockIfNeeded(output, state);
                        emitCompletedWebSearchBlocks(output, item, state);
                        continue;
                    }
                    closeActiveBlockIfNeeded(output, state);
                    continue;
                }
                if (isTerminalEvent(type)) {
                    closeActiveBlockIfNeeded(output, state);
                    ObjectNode responseNode = event.get("response") instanceof ObjectNode node ? node : null;
                    bindContinuationResponse(runtimeContext, account, continuationContext, responseNode);
                    ObjectNode terminalUsage = extractUsage(responseNode);
                    String stopReason = extractStopReason(responseNode, state.hasToolCall);
                    writeSse(output, "message_delta", buildMessageDeltaEvent(stopReason, terminalUsage));
                    writeSse(output, "message_stop", objectMapper.createObjectNode().put("type", "message_stop"));
                    output.flush();
                    response.flushBuffer();
                    return;
                }
            }
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "api_error", "Failed to stream Anthropic response");
        }
        throw new AnthropicApiErrorException(502, "api_error", "Upstream stream ended without a terminal response event");
    }

    private boolean isTerminalEvent(String type) {
        return "response.completed".equals(type)
                || "response.done".equals(type)
                || "response.incomplete".equals(type)
                || "response.failed".equals(type);
    }

    private ObjectNode readTerminalResponse(HttpResponse<InputStream> upstream) {
        BufferedMessagesAccumulator accumulator = new BufferedMessagesAccumulator();
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
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
                accumulator.process(event);
                if (isTerminalEvent(type) && event.get("response") instanceof ObjectNode responseNode) {
                    ObjectNode terminal = responseNode.deepCopy();
                    accumulator.supplement(terminal);
                    return terminal;
                }
            }
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "api_error", "Failed to read OpenAI upstream response");
        }
        throw new AnthropicApiErrorException(502, "api_error", "Upstream stream ended without a terminal response event");
    }

    private ObjectNode buildMessageStartEvent(JsonNode event, StreamState state) {
        ObjectNode message = objectMapper.createObjectNode();
        JsonNode response = event.get("response");
        String responseId = text(response == null ? null : response.get("id"));
        String model = state.originalModel == null || state.originalModel.isBlank()
                ? text(response == null ? null : response.get("model"))
                : state.originalModel;
        message.put("id", responseId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.set("content", objectMapper.createArrayNode());
        message.set("usage", zeroUsage());

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("type", "message_start");
        wrapper.set("message", message);
        return wrapper;
    }

    private void closeActiveBlockIfNeeded(ServletOutputStream output, StreamState state) throws IOException {
        if (state.contentBlockStarted && !state.contentBlockClosed) {
            writeSse(output, "content_block_stop", buildContentBlockStopEvent(state.currentBlockIndex));
            state.contentBlockClosed = true;
            state.nextBlockIndex = Math.max(state.nextBlockIndex, state.currentBlockIndex + 1);
        }
        state.contentBlockStarted = false;
        state.currentBlockType = null;
        state.currentToolCallId = null;
        state.currentToolHadDelta = false;
        state.currentBlockIndex = -1;
    }

    private int openContentBlock(StreamState state, String blockType) {
        state.contentBlockStarted = true;
        state.contentBlockClosed = false;
        state.currentBlockType = blockType;
        state.currentBlockIndex = state.nextBlockIndex;
        return state.currentBlockIndex;
    }

    private ObjectNode buildTextContentBlockStartEvent(int index) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "text");
        block.put("text", "");

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        event.set("content_block", block);
        return event;
    }

    private ObjectNode buildToolUseContentBlockStartEvent(JsonNode item, int index) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", text(item == null ? null : item.get("call_id")));
        block.put("name", text(item == null ? null : item.get("name")));
        block.putObject("input");

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        event.set("content_block", block);
        return event;
    }

    private ObjectNode buildThinkingContentBlockStartEvent(int index) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "thinking");
        block.put("thinking", "");

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        event.set("content_block", block);
        return event;
    }

    private ObjectNode buildServerToolUseContentBlockStartEvent(String toolUseId, String query, int index) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "server_tool_use");
        block.put("id", toolUseId);
        block.put("name", "web_search");
        ObjectNode input = block.putObject("input");
        input.put("query", query);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        event.set("content_block", block);
        return event;
    }

    private ObjectNode buildWebSearchToolResultContentBlockStartEvent(String toolUseId, int index) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "web_search_tool_result");
        block.put("tool_use_id", toolUseId);
        block.set("content", objectMapper.createArrayNode());

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        event.set("content_block", block);
        return event;
    }

    private ObjectNode buildContentBlockDeltaEvent(int index, String delta) {
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put("type", "text_delta");
        deltaNode.put("text", delta);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        event.set("delta", deltaNode);
        return event;
    }

    private ObjectNode buildInputJsonDeltaEvent(int index, String delta) {
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put("type", "input_json_delta");
        deltaNode.put("partial_json", delta);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        event.set("delta", deltaNode);
        return event;
    }

    private ObjectNode buildThinkingDeltaEvent(int index, String delta) {
        ObjectNode deltaNode = objectMapper.createObjectNode();
        deltaNode.put("type", "thinking_delta");
        deltaNode.put("thinking", delta);

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        event.set("delta", deltaNode);
        return event;
    }

    private ObjectNode buildContentBlockStopEvent(int index) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_stop");
        event.put("index", index);
        return event;
    }

    private void emitCompletedWebSearchBlocks(ServletOutputStream output, JsonNode item, StreamState state) throws IOException {
        String searchId = text(item == null ? null : item.get("id"));
        if (searchId.isEmpty()) {
            return;
        }
        String toolUseId = "srvtoolu_" + searchId;
        String query = extractWebSearchQuery(item == null ? null : item.get("action"));
        int serverToolIndex = state.nextBlockIndex;
        writeSse(output, "content_block_start", buildServerToolUseContentBlockStartEvent(toolUseId, query, serverToolIndex));
        writeSse(output, "content_block_stop", buildContentBlockStopEvent(serverToolIndex));

        int resultIndex = serverToolIndex + 1;
        writeSse(output, "content_block_start", buildWebSearchToolResultContentBlockStartEvent(toolUseId, resultIndex));
        writeSse(output, "content_block_stop", buildContentBlockStopEvent(resultIndex));
        state.nextBlockIndex = resultIndex + 1;
    }

    private ObjectNode buildMessageDeltaEvent(String stopReason, ObjectNode usage) {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("stop_reason", stopReason);
        delta.putNull("stop_sequence");

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message_delta");
        event.set("delta", delta);
        event.set("usage", usage == null ? zeroUsage() : usage);
        return event;
    }

    private void writeSse(ServletOutputStream output, String eventName, ObjectNode payload) throws IOException {
        output.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + writeJsonString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private ObjectNode toAnthropicResponse(ObjectNode responseNode, String originalModel) {
        ArrayNode content = extractContent(responseNode);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("id", text(responseNode.get("id")));
        result.put("type", "message");
        result.put("role", "assistant");
        result.put("model", originalModel);
        result.set("content", content);
        result.put("stop_reason", extractStopReason(responseNode, containsToolUseLikeBlock(content)));
        result.putNull("stop_sequence");
        result.set("usage", extractUsage(responseNode));
        return result;
    }

    private ArrayNode extractContent(ObjectNode responseNode) {
        ArrayNode content = objectMapper.createArrayNode();
        JsonNode output = responseNode.get("output");
        if (output instanceof ArrayNode items) {
            for (JsonNode item : items) {
                String itemType = text(item.get("type"));
                if ("message".equals(itemType)) {
                    JsonNode parts = item.get("content");
                    if (!(parts instanceof ArrayNode contentParts)) {
                        continue;
                    }
                    for (JsonNode part : contentParts) {
                        appendBufferedContentBlock(content, part);
                    }
                    continue;
                }
                if ("function_call".equals(itemType)) {
                    appendBufferedToolUseBlock(content, item);
                    continue;
                }
                if ("web_search_call".equals(itemType)) {
                    appendBufferedWebSearchBlocks(content, item);
                    continue;
                }
                if ("reasoning".equals(itemType)) {
                    appendBufferedThinkingBlock(content, item);
                }
            }
        }
        if (content.isEmpty()) {
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", "text");
            block.put("text", "");
            content.add(block);
        }
        return content;
    }

    private boolean containsToolUseLikeBlock(ArrayNode content) {
        if (content == null) {
            return false;
        }
        for (JsonNode block : content) {
            String type = text(block == null ? null : block.get("type"));
            if ("tool_use".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private void appendBufferedContentBlock(ArrayNode content, JsonNode part) {
        String partType = text(part == null ? null : part.get("type"));
        if ("output_text".equals(partType)) {
            String value = text(part.get("text"));
            if (value.isEmpty()) {
                return;
            }
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", "text");
            block.put("text", value);
            content.add(block);
            return;
        }
        if ("refusal".equals(partType)) {
            String value = text(part.get("refusal"));
            if (value.isEmpty()) {
                value = text(part.get("text"));
            }
            if (value.isEmpty()) {
                return;
            }
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", "text");
            block.put("text", value);
            content.add(block);
        }
    }

    private void appendBufferedToolUseBlock(ArrayNode content, JsonNode item) {
        String id = text(item == null ? null : item.get("call_id"));
        String name = text(item == null ? null : item.get("name"));
        if (id.isEmpty() || name.isEmpty()) {
            return;
        }
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        JsonNode arguments = item.get("arguments");
        if (arguments == null || arguments.isNull()) {
            block.putObject("input");
            content.add(block);
            return;
        }
        if (arguments.isObject() || arguments.isArray()) {
            block.set("input", arguments.deepCopy());
            content.add(block);
            return;
        }
        String raw = arguments.isTextual() ? arguments.asText() : compactJson(arguments);
        if (raw == null || raw.isBlank()) {
            block.putObject("input");
            content.add(block);
            return;
        }
        try {
            block.set("input", objectMapper.readTree(raw));
        } catch (IOException ex) {
            block.put("input_text", raw);
        }
        content.add(block);
    }

    private void appendBufferedThinkingBlock(ArrayNode content, JsonNode item) {
        String thinking = extractReasoningText(item);
        if (thinking.isEmpty()) {
            return;
        }
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "thinking");
        block.put("thinking", thinking);
        content.add(block);
    }

    private void appendBufferedWebSearchBlocks(ArrayNode content, JsonNode item) {
        String searchId = text(item == null ? null : item.get("id"));
        if (searchId.isEmpty()) {
            return;
        }
        String toolUseId = "srvtoolu_" + searchId;

        ObjectNode serverToolUse = objectMapper.createObjectNode();
        serverToolUse.put("type", "server_tool_use");
        serverToolUse.put("id", toolUseId);
        serverToolUse.put("name", "web_search");
        ObjectNode input = serverToolUse.putObject("input");
        input.put("query", extractWebSearchQuery(item.get("action")));
        content.add(serverToolUse);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "web_search_tool_result");
        result.put("tool_use_id", toolUseId);
        result.set("content", objectMapper.createArrayNode());
        content.add(result);
    }

    private String extractReasoningText(JsonNode item) {
        if (item == null || item.isNull()) {
            return "";
        }
        String summaryText = text(item.get("summary_text"));
        if (!summaryText.isEmpty()) {
            return summaryText;
        }
        JsonNode summary = item.get("summary");
        if (summary instanceof ArrayNode array) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : array) {
                String value = text(part.get("text"));
                if (value.isEmpty()) {
                    value = text(part);
                }
                if (value.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("\n");
                }
                builder.append(value);
            }
            if (builder.length() > 0) {
                return builder.toString();
            }
        }
        JsonNode textNode = item.get("text");
        if (textNode != null && textNode.isTextual()) {
            return textNode.asText().trim();
        }
        return "";
    }

    private String extractStopReason(ObjectNode responseNode, boolean hasToolCall) {
        if (responseNode == null) {
            return "end_turn";
        }
        String status = text(responseNode.get("status"));
        if ("incomplete".equals(status)) {
            JsonNode details = responseNode.get("incomplete_details");
            if (details != null && "max_output_tokens".equals(text(details.get("reason")))) {
                return "max_tokens";
            }
        }
        if ("completed".equals(status) && hasToolCall) {
            return "tool_use";
        }
        return "end_turn";
    }

    private ObjectNode extractUsage(JsonNode responseNode) {
        ObjectNode usage = objectMapper.createObjectNode();
        JsonNode upstreamUsage = responseNode == null ? null : responseNode.get("usage");
        int inputTokens = intValue(upstreamUsage == null ? null : upstreamUsage.get("input_tokens"));
        int outputTokens = intValue(upstreamUsage == null ? null : upstreamUsage.get("output_tokens"));
        int cachedTokens = intValue(upstreamUsage == null || upstreamUsage.get("input_tokens_details") == null
                ? null
                : upstreamUsage.get("input_tokens_details").get("cached_tokens"));
        usage.put("input_tokens", Math.max(0, inputTokens - cachedTokens));
        usage.put("output_tokens", Math.max(0, outputTokens));
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", Math.max(0, cachedTokens));
        return usage;
    }

    private ObjectNode zeroUsage() {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        return usage;
    }

    private AnthropicApiErrorException translateUpstreamError(int statusCode, String message) {
        if (statusCode >= 500) {
            return new AnthropicApiErrorException(502, "api_error", message);
        }
        if (statusCode == 401 || statusCode == 403) {
            return new AnthropicApiErrorException(statusCode, "permission_error", message);
        }
        return new AnthropicApiErrorException(statusCode, "invalid_request_error", message);
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
            message = extractOpenAiErrorMessage(body, message);
        } catch (Exception ignored) {
        }
        return message;
    }

    private BufferedUpstreamError readBufferedUpstreamError(HttpResponse<InputStream> upstream) {
        byte[] body = new byte[0];
        try (InputStream input = upstream.body()) {
            body = input == null ? new byte[0] : input.readAllBytes();
        } catch (Exception ignored) {
        }
        return new BufferedUpstreamError(
                upstream == null ? 0 : upstream.statusCode(),
                body,
                extractOpenAiErrorMessage(body, "OpenAI upstream request failed")
        );
    }

    private String extractOpenAiErrorMessage(byte[] body, String fallback) {
        if (body == null || body.length == 0) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isObject()) {
                String upstreamMessage = text(error.get("message"));
                if (!upstreamMessage.isEmpty()) {
                    return upstreamMessage;
                }
            }
        } catch (Exception ignored) {
        }
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        return raw.isEmpty() ? fallback : raw;
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

    private byte[] writeJsonBytes(JsonNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(500, "api_error", "Failed to encode OpenAI dispatch request");
        }
    }

    private String writeJsonString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(500, "api_error", "Failed to encode Anthropic response");
        }
    }

    private int intValue(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : 0;
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    private void validateCompatibilityShape(ObjectNode objectNode) {
        if (objectNode == null) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
        }
        JsonNode tools = objectNode.get("tools");
        if (tools != null && !tools.isNull() && !tools.isArray()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "tools must be an array");
        }
        JsonNode thinking = objectNode.get("thinking");
        if (thinking != null && !thinking.isNull() && !thinking.isObject()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "thinking must be an object");
        }
        JsonNode stopSequences = objectNode.get("stop_sequences");
        if (stopSequences != null && !stopSequences.isNull()) {
            if (!stopSequences.isArray()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "stop_sequences must be an array");
            }
            for (JsonNode item : stopSequences) {
                if (!item.isTextual()) {
                    throw new AnthropicApiErrorException(400, "invalid_request_error", "stop_sequences must contain strings");
                }
            }
        }
        JsonNode system = objectNode.get("system");
        if (system != null && !system.isNull() && !system.isTextual() && !system.isArray()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "system must be text or an array");
        }
        JsonNode messages = objectNode.get("messages");
        if (!(messages instanceof ArrayNode items) || items.isEmpty()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "messages must be a non-empty array");
        }
        for (JsonNode item : items) {
            if (!(item instanceof ObjectNode messageNode)) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "messages must contain objects");
            }
            JsonNode contentNode = messageNode.get("content");
            if (contentNode == null) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "message content is required");
            }
            if (contentNode.isTextual()) {
                continue;
            }
            if (!contentNode.isArray()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "message content must be text or array");
            }
            for (JsonNode block : contentNode) {
                if (block == null || !block.isObject()) {
                    throw new AnthropicApiErrorException(400, "invalid_request_error", "message content blocks must be objects");
                }
            }
        }
    }

    private ContinuationContext applyContinuationState(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            ObjectNode responsesRequest
    ) {
        if (responsesRequest == null) {
            return new ContinuationContext("", "", false);
        }
        String promptCacheKey = text(responsesRequest.get("prompt_cache_key"));
        String model = text(responsesRequest.get("model"));
        boolean enabled = isContinuationEnabled(account, model) && !promptCacheKey.isBlank();
        if (!enabled) {
            return new ContinuationContext(promptCacheKey, "", false);
        }
        ContinuationBinding binding = readContinuationBinding(runtimeContext, account, promptCacheKey);
        if (binding == null || binding.continuationDisabled() || binding.responseId().isBlank()) {
            return new ContinuationContext(promptCacheKey, "", true);
        }
        responsesRequest.put("previous_response_id", binding.responseId());
        trimResponsesInputToLatestTurn(responsesRequest);
        return new ContinuationContext(promptCacheKey, binding.responseId(), true);
    }

    private boolean maybeRecoverFromPreviousResponseFailure(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            ObjectNode anthropicRequest,
            BufferedUpstreamError bufferedError,
            ContinuationContext continuationContext
    ) {
        if (bufferedError == null
                || continuationContext == null
                || continuationContext.promptCacheKey().isBlank()
                || continuationContext.previousResponseId().isBlank()) {
            return false;
        }
        boolean unsupported = isPreviousResponseUnsupported(bufferedError.statusCode(), bufferedError.message(), bufferedError.body());
        boolean notFound = isPreviousResponseNotFound(bufferedError.statusCode(), bufferedError.message(), bufferedError.body());
        if (!unsupported && !notFound) {
            return false;
        }
        if (unsupported) {
            disableContinuation(runtimeContext, account, continuationContext.promptCacheKey());
            return true;
        }
        deleteContinuationResponseId(runtimeContext, account, continuationContext.promptCacheKey());
        return true;
    }

    private boolean isContinuationEnabled(AdminAccountResponse account, String model) {
        return account != null
                && "apikey".equalsIgnoreCase(account.type())
                && shouldAutoInjectPromptCacheKeyForCompat(model);
    }

    private boolean shouldAutoInjectPromptCacheKeyForCompat(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return (!normalized.isEmpty())
                && (normalized.contains("gpt-5") || normalized.contains("codex"));
    }

    private String deriveAnthropicCompatPromptCacheKey(ObjectNode anthropicRequest, String mappedModel) {
        if (anthropicRequest == null) {
            return "";
        }
        List<String> seedParts = new ArrayList<>();
        String normalizedModel = mappedModel == null ? "" : mappedModel.trim();
        if (normalizedModel.isEmpty()) {
            normalizedModel = requireModel(anthropicRequest);
        }
        if (!normalizedModel.isEmpty()) {
            seedParts.add("model=" + normalizedModel.toLowerCase(Locale.ROOT));
        }
        JsonNode outputConfig = anthropicRequest.get("output_config");
        if (outputConfig instanceof ObjectNode outputConfigNode) {
            String effort = text(outputConfigNode.get("effort"));
            if (!effort.isEmpty()) {
                seedParts.add("effort=" + effort);
            }
        }
        JsonNode toolChoice = anthropicRequest.get("tool_choice");
        if (toolChoice != null && !toolChoice.isNull()) {
            seedParts.add("tool_choice=" + compactJson(toolChoice));
        }
        JsonNode tools = anthropicRequest.get("tools");
        if (tools != null && !tools.isNull()) {
            seedParts.add("tools=" + compactJson(tools));
        }
        JsonNode system = anthropicRequest.get("system");
        if (system != null && !system.isNull()) {
            seedParts.add("system=" + compactJson(system));
        }
        JsonNode messages = anthropicRequest.get("messages");
        if (messages instanceof ArrayNode items) {
            for (JsonNode item : items) {
                if (!(item instanceof ObjectNode messageNode)) {
                    continue;
                }
                if (!"user".equalsIgnoreCase(text(messageNode.get("role")))) {
                    continue;
                }
                JsonNode content = messageNode.get("content");
                if (content != null && !content.isNull()) {
                    seedParts.add("first_user=" + compactJson(content));
                    break;
                }
            }
        }
        if (seedParts.isEmpty()) {
            return "";
        }
        return COMPAT_PROMPT_CACHE_KEY_PREFIX + sha256Hex(String.join("|", seedParts));
    }

    private void trimResponsesInputToLatestTurn(ObjectNode responsesRequest) {
        if (responsesRequest == null) {
            return;
        }
        JsonNode inputNode = responsesRequest.get("input");
        if (!(inputNode instanceof ArrayNode input) || input.isEmpty()) {
            return;
        }
        int start = input.size() - 1;
        while (start > 0) {
            JsonNode item = input.get(start);
            if (!(item instanceof ObjectNode objectNode) || !"function_call_output".equals(text(objectNode.get("type")))) {
                break;
            }
            start--;
        }
        if (start <= 0) {
            return;
        }
        ArrayNode trimmed = objectMapper.createArrayNode();
        for (int i = start; i < input.size(); i++) {
            trimmed.add(input.get(i).deepCopy());
        }
        if (trimmed.size() != input.size()) {
            responsesRequest.set("input", trimmed);
        }
    }

    private boolean isPreviousResponseNotFound(int statusCode, String message, byte[] body) {
        if (statusCode != 400 && statusCode != 404) {
            return false;
        }
        return containsPreviousResponseSignal(message, "previous_response_not_found")
                || containsPreviousResponseSignal(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8), "previous_response_not_found")
                || containsPreviousResponsePhrase(message, "previous response", "not found")
                || containsPreviousResponsePhrase(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8), "previous response", "not found")
                || containsPreviousResponsePhrase(message, "unsupported parameter", "previous_response_id")
                || containsPreviousResponsePhrase(new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8), "unsupported parameter", "previous_response_id");
    }

    private boolean isPreviousResponseUnsupported(int statusCode, String message, byte[] body) {
        if (statusCode != 400) {
            return false;
        }
        String raw = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        return containsPreviousResponsePhrase(message, "previous_response_id", "unsupported parameter")
                || containsPreviousResponsePhrase(raw, "previous_response_id", "unsupported parameter")
                || containsPreviousResponsePhrase(message, "previous_response_id", "not supported")
                || containsPreviousResponsePhrase(raw, "previous_response_id", "not supported")
                || containsPreviousResponsePhrase(message, "previous_response_id", "only supported on responses websocket")
                || containsPreviousResponsePhrase(raw, "previous_response_id", "only supported on responses websocket");
    }

    private boolean containsPreviousResponseSignal(String raw, String signal) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && normalized.contains(signal);
    }

    private boolean containsPreviousResponsePhrase(String raw, String requiredOne, String requiredTwo) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && normalized.contains(requiredOne) && normalized.contains(requiredTwo);
    }

    private void bindContinuationResponse(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            ContinuationContext continuationContext,
            ObjectNode responseNode
    ) {
        if (continuationContext == null || !continuationContext.enabled() || responseNode == null) {
            return;
        }
        String responseId = text(responseNode.get("id"));
        if (responseId.isBlank()) {
            return;
        }
        bindContinuationResponseId(runtimeContext, account, continuationContext.promptCacheKey(), responseId);
    }

    private void bindContinuationResponseId(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            String promptCacheKey,
            String responseId
    ) {
        String key = continuationKey(runtimeContext, account, promptCacheKey);
        if (key.isBlank() || responseId == null || responseId.isBlank()) {
            return;
        }
        ContinuationBinding existing = readContinuationBinding(runtimeContext, account, promptCacheKey);
        if (existing != null && existing.continuationDisabled()) {
            continuationBindings.put(key, new ContinuationBinding("", true, expiresAtMillis()));
            return;
        }
        continuationBindings.put(key, new ContinuationBinding(responseId.trim(), false, expiresAtMillis()));
    }

    private void deleteContinuationResponseId(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            String promptCacheKey
    ) {
        String key = continuationKey(runtimeContext, account, promptCacheKey);
        if (key.isBlank()) {
            return;
        }
        ContinuationBinding existing = continuationBindings.get(key);
        if (existing == null) {
            return;
        }
        if (existing.continuationDisabled()) {
            continuationBindings.put(key, new ContinuationBinding("", true, expiresAtMillis()));
            return;
        }
        continuationBindings.remove(key);
    }

    private void disableContinuation(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            String promptCacheKey
    ) {
        String key = continuationKey(runtimeContext, account, promptCacheKey);
        if (key.isBlank()) {
            return;
        }
        continuationBindings.put(key, new ContinuationBinding("", true, expiresAtMillis()));
    }

    private ContinuationBinding readContinuationBinding(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            String promptCacheKey
    ) {
        String key = continuationKey(runtimeContext, account, promptCacheKey);
        if (key.isBlank()) {
            return null;
        }
        ContinuationBinding binding = continuationBindings.get(key);
        if (binding == null) {
            return null;
        }
        if (binding.expiresAtMillis() > 0 && System.currentTimeMillis() > binding.expiresAtMillis()) {
            continuationBindings.remove(key, binding);
            return null;
        }
        return binding;
    }

    private String continuationKey(
            GatewayRuntimeContext runtimeContext,
            AdminAccountResponse account,
            String promptCacheKey
    ) {
        if (runtimeContext == null
                || runtimeContext.apiKey() == null
                || account == null
                || promptCacheKey == null
                || promptCacheKey.isBlank()) {
            return "";
        }
        return account.id() + "\u0000" + runtimeContext.apiKey().id() + "\u0000" + promptCacheKey.trim();
    }

    private long expiresAtMillis() {
        return System.currentTimeMillis() + CONTINUATION_TTL.toMillis();
    }

    private String rawTextOrJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText().trim();
        }
        return compactJson(node);
    }

    private boolean isIgnoredHistoryBlockType(String blockType) {
        return "thinking".equals(blockType)
                || "redacted_thinking".equals(blockType)
                || "server_tool_use".equals(blockType)
                || "web_search_tool_result".equals(blockType);
    }

    private String resolveHeaderOrDefault(HttpServletRequest request, String name, String fallback) {
        String direct = request == null ? null : request.getHeader(name);
        if ((direct == null || direct.isBlank()) && request != null) {
            direct = request.getHeader(name.toLowerCase(Locale.ROOT));
        }
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return "assistant".equals(normalized) ? "assistant" : "user";
    }

    private ObjectNode textPart(String value) {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("type", "input_text");
        part.put("text", value);
        return part;
    }

    private ObjectNode outputTextPart(String value) {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("type", "output_text");
        part.put("text", value);
        return part;
    }

    private JsonNode normalizeToolParameters(JsonNode inputSchema) {
        if (!(inputSchema instanceof ObjectNode schemaObject)) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", objectMapper.createObjectNode());
            return fallback;
        }
        ObjectNode normalized = schemaObject.deepCopy();
        if (!"object".equals(text(normalized.get("type")))) {
            return normalized;
        }
        JsonNode properties = normalized.get("properties");
        if (properties == null || properties.isNull() || !properties.isObject()) {
            normalized.set("properties", objectMapper.createObjectNode());
        }
        return normalized;
    }

    private ArrayNode convertTools(JsonNode toolsNode) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (!(toolsNode instanceof ArrayNode source)) {
            return tools;
        }
        for (JsonNode item : source) {
            if (!(item instanceof ObjectNode toolNode)) {
                continue;
            }
            String toolType = text(toolNode.get("type"));
            if (toolType.startsWith("web_search")) {
                ObjectNode tool = objectMapper.createObjectNode();
                tool.put("type", "web_search");
                tools.add(tool);
                continue;
            }
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            tool.put("name", text(toolNode.get("name")));
            if (toolNode.has("description")) {
                tool.set("description", toolNode.get("description"));
            }
            tool.set("parameters", normalizeToolParameters(toolNode.get("input_schema")));
            tools.add(tool);
        }
        return tools;
    }

    private String extractToolResultOutput(JsonNode contentNode, List<String> imageCollector) {
        if (contentNode == null || contentNode.isNull()) {
            return "(empty)";
        }
        if (contentNode.isTextual()) {
            String value = contentNode.asText();
            return value.isBlank() ? "(empty)" : value;
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode block : contentNode) {
                String blockType = text(block.get("type"));
                if ("image".equals(blockType)) {
                    String imageUrl = anthropicImageToDataUri(block.get("source"));
                    if (!imageUrl.isEmpty()) {
                        imageCollector.add(imageUrl);
                    }
                    continue;
                }
                if (!"text".equals(blockType)) {
                    continue;
                }
                String value = text(block.get("text"));
                if (value.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("\n\n");
                }
                builder.append(value);
            }
            return builder.length() == 0 ? "(empty)" : builder.toString();
        }
        return compactJson(contentNode);
    }

    private String anthropicImageToDataUri(JsonNode sourceNode) {
        if (!(sourceNode instanceof ObjectNode source)) {
            return "";
        }
        String data = text(source.get("data"));
        if (data.isEmpty()) {
            return "";
        }
        String mediaType = text(source.get("media_type"));
        if (mediaType.isEmpty()) {
            mediaType = "image/png";
        }
        return "data:" + mediaType + ";base64," + data;
    }

    private String extractWebSearchQuery(JsonNode actionNode) {
        if (!(actionNode instanceof ObjectNode action)) {
            return "";
        }
        return text(action.get("query"));
    }

    private ObjectNode imagePart(String imageUrl) {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("type", "input_image");
        part.put("image_url", imageUrl);
        return part;
    }

    private String compactJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private ParsedMetadataUserId parseMetadataUserId(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(normalized);
                if (!(node instanceof ObjectNode objectNode)) {
                    return null;
                }
                String deviceId = text(objectNode.get("device_id"));
                String accountUuid = text(objectNode.get("account_uuid"));
                String sessionId = text(objectNode.get("session_id"));
                if (deviceId.isEmpty() || sessionId.isEmpty()) {
                    return null;
                }
                return new ParsedMetadataUserId(deviceId, accountUuid, sessionId);
            } catch (Exception ignored) {
                return null;
            }
        }
        Matcher matcher = LEGACY_METADATA_USER_ID_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        return new ParsedMetadataUserId(
                matcher.group(1) == null ? "" : matcher.group(1).trim(),
                matcher.group(2) == null ? "" : matcher.group(2).trim(),
                matcher.group(3) == null ? "" : matcher.group(3).trim()
        );
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 8 && i < hash.length; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(500, "api_error", "Failed to derive prompt_cache_key");
        }
    }

    private record ParsedMetadataUserId(String deviceId, String accountUuid, String sessionId) {
    }

    private record BufferedUpstreamError(int statusCode, byte[] body, String message) {
    }

    private record CompatRequestedModelNormalization(String normalizedModel, String reasoningEffort, boolean hasReasoningSuffix) {
    }

    private record ContinuationBinding(String responseId, boolean continuationDisabled, long expiresAtMillis) {
    }

    private record ContinuationContext(String promptCacheKey, String previousResponseId, boolean enabled) {
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

    private static final class StreamState {
        private final String originalModel;
        private boolean contentBlockStarted;
        private boolean contentBlockClosed;
        private String currentBlockType;
        private String currentToolCallId;
        private boolean currentToolHadDelta;
        private boolean hasToolCall;
        private int currentBlockIndex = -1;
        private int nextBlockIndex;

        private StreamState(String originalModel) {
            this.originalModel = originalModel;
        }
    }

    private final class BufferedMessagesAccumulator {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final List<ObjectNode> functionCalls = new ArrayList<>();
        private final Map<String, ObjectNode> webSearchCalls = new LinkedHashMap<>();
        private final Map<Integer, Integer> outputIndexToFunctionIndex = new LinkedHashMap<>();

        private void process(JsonNode event) {
            String type = text(event == null ? null : event.get("type"));
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
                String itemType = text(item == null ? null : item.get("type"));
                if ("function_call".equals(itemType)) {
                    ObjectNode functionCall = objectMapper.createObjectNode();
                    functionCall.put("type", "function_call");
                    functionCall.put("call_id", text(item.get("call_id")));
                    functionCall.put("name", text(item.get("name")));
                    functionCall.put("arguments", "");
                    int outputIndex = event.path("output_index").asInt(-1);
                    outputIndexToFunctionIndex.put(outputIndex, functionCalls.size());
                    functionCalls.add(functionCall);
                    return;
                }
                if ("web_search_call".equals(itemType)) {
                    upsertWebSearchCall(item);
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
                return;
            }
            if ("response.output_item.done".equals(type)) {
                JsonNode item = event.get("item");
                if (item != null && "web_search_call".equals(text(item.get("type")))) {
                    upsertWebSearchCall(item);
                }
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
            for (ObjectNode webSearchCall : webSearchCalls.values()) {
                rebuilt.add(webSearchCall);
            }
            responseNode.set("output", rebuilt);
        }

        private void upsertWebSearchCall(JsonNode item) {
            String id = text(item == null ? null : item.get("id"));
            if (id.isEmpty()) {
                return;
            }
            ObjectNode webSearch = webSearchCalls.computeIfAbsent(id, ignored -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", "web_search_call");
                node.put("id", id);
                return node;
            });
            if (item.has("status")) {
                webSearch.set("status", item.get("status"));
            }
            if (item.has("action")) {
                webSearch.set("action", item.get("action"));
            }
        }
    }
}
