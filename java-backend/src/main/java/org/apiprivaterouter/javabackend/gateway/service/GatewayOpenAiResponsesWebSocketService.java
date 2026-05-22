package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class GatewayOpenAiResponsesWebSocketService {

    private static final String OPENAI_WS_BETA_V2_VALUE = "responses_websockets=2026-02-06";
    private static final String OPENAI_CODEX_USER_AGENT = "codex_cli_rs/0.125.0";
    private static final String TURN_STATE_HEADER = "x-codex-turn-state";
    private static final String TURN_METADATA_HEADER = "x-codex-turn-metadata";
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept-language",
            "conversation_id",
            "originator",
            "session_id",
            "user-agent",
            TURN_STATE_HEADER,
            TURN_METADATA_HEADER
    );
    private static final Set<String> CLIENT_REQUEST_HEADER_BLOCKLIST = Set.of(
            "authorization",
            "connection",
            "content-length",
            "host",
            "openai-beta",
            "sec-websocket-extensions",
            "sec-websocket-key",
            "sec-websocket-protocol",
            "sec-websocket-version",
            "upgrade"
    );

    private final AdminAccountRepository accountRepository;
    private final GatewayOpenAiResponsesService responsesService;
    private final GatewayOpenAiFastPolicyService fastPolicyService;
    private final ObjectMapper objectMapper;

    public GatewayOpenAiResponsesWebSocketService(
            AdminAccountRepository accountRepository,
            GatewayOpenAiResponsesService responsesService,
            GatewayOpenAiFastPolicyService fastPolicyService,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.responsesService = responsesService;
        this.fastPolicyService = fastPolicyService;
        this.objectMapper = objectMapper;
    }

    public AdminAccountResponse requireAccount(GatewayRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible accounts");
        }
        return accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new OpenAiApiErrorException(503, "api_error", "No available compatible accounts"));
    }

    public TextMessage prepareClientMessage(WebSocketSession session, String payload, AdminAccountResponse account, boolean firstFrame) {
        if (payload == null || payload.isBlank()) {
            throw closeError(CloseStatus.POLICY_VIOLATION, "empty websocket request payload");
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                throw closeError(CloseStatus.POLICY_VIOLATION, "invalid websocket request payload");
            }
            ObjectNode normalized = objectNode.deepCopy();
            String eventType = stringField(normalized.get("type"));
            if (eventType.isEmpty()) {
                normalized.put("type", "response.create");
                eventType = "response.create";
            }
            if ("session.update".equals(eventType)) {
                normalizeSessionUpdateModel(normalized, account);
                return new TextMessage(objectMapper.writeValueAsString(normalized));
            }
            if (!"response.create".equals(eventType)) {
                if ("response.append".equals(eventType)) {
                    throw closeError(CloseStatus.POLICY_VIOLATION, "response.append is not supported in ws v2; use response.create with previous_response_id");
                }
                applyTurnMetadataClientMetadata(normalized, session);
                return new TextMessage(objectMapper.writeValueAsString(normalized));
            }
            JsonNode modelNode = normalized.get("model");
            String requestedModel = modelNode != null && modelNode.isTextual() ? modelNode.asText().trim() : "";
            if (requestedModel.isBlank()) {
                requestedModel = resolveSessionModel(session);
                if (!requestedModel.isBlank()) {
                    normalized.put("model", requestedModel);
                }
            }
            if (requestedModel.isBlank()) {
                String reason = firstFrame
                        ? "model is required in first response.create payload"
                        : "model is required in response.create payload";
                throw closeError(CloseStatus.POLICY_VIOLATION, reason);
            }
            String previousResponseId = stringField(normalized.get("previous_response_id"));
            if (looksLikeMessageId(previousResponseId)) {
                throw closeError(CloseStatus.POLICY_VIOLATION, "previous_response_id must be a response.id (resp_*), not a message id");
            }
            normalized.remove("background");
            if (isOauthLikeType(account.type()) && !isStoreRecoveryAllowed(account) && !normalized.has("store")) {
                normalized.put("store", false);
            }
            if (!normalized.has("stream")) {
                normalized.put("stream", true);
            }
            applyTurnMetadataClientMetadata(normalized, session);
            String mappedModel = responsesService.resolveMappedModel(account, requestedModel);
            if (!mappedModel.equals(requestedModel)) {
                normalized.put("model", mappedModel);
            }
            JsonNode serviceTier = normalized.get("service_tier");
            if (serviceTier != null && serviceTier.isTextual()) {
                GatewayOpenAiFastPolicyService.FastPolicyApplyResult fastPolicyResult =
                        fastPolicyService.applyToResponseCreateFrame(account, mappedModel, normalized);
                if (fastPolicyResult.action() == GatewayOpenAiFastPolicyService.Action.BLOCK) {
                    throw blockedError(fastPolicyResult.blockMessage());
                }
                normalized = fastPolicyResult.payload();
            }
            return new TextMessage(objectMapper.writeValueAsString(normalized));
        } catch (GatewayResponsesWebSocketCloseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw closeError(CloseStatus.POLICY_VIOLATION, "invalid websocket request payload");
        }
    }

    public TextMessage preparePassthroughClientMessage(WebSocketSession session, String payload, AdminAccountResponse account) {
        if (payload == null || payload.isBlank()) {
            throw closeError(CloseStatus.POLICY_VIOLATION, "empty websocket request payload");
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                throw closeError(CloseStatus.POLICY_VIOLATION, "invalid websocket request payload");
            }
            String eventType = stringField(objectNode.get("type"));
            if ("response.append".equals(eventType)) {
                throw closeError(CloseStatus.POLICY_VIOLATION, "response.append is not supported in ws v2; use response.create with previous_response_id");
            }
            if (!"response.create".equals(eventType)) {
                return new TextMessage(payload);
            }
            ObjectNode normalized = objectNode.deepCopy();
            String policyModel = resolvePassthroughPolicyModel(session, normalized, account);
            GatewayOpenAiFastPolicyService.FastPolicyApplyResult fastPolicyResult =
                    fastPolicyService.applyToResponseCreateFrame(account, policyModel, normalized);
            if (fastPolicyResult.action() == GatewayOpenAiFastPolicyService.Action.BLOCK) {
                throw blockedError(fastPolicyResult.blockMessage());
            }
            String serialized = objectMapper.writeValueAsString(fastPolicyResult.payload());
            return serialized.equals(payload) ? new TextMessage(payload) : new TextMessage(serialized);
        } catch (GatewayResponsesWebSocketCloseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw closeError(CloseStatus.POLICY_VIOLATION, "invalid websocket request payload");
        }
    }

    public TextMessage applyContinuationHints(TextMessage message, String previousResponseId) {
        if (message == null || previousResponseId == null || previousResponseId.isBlank()) {
            return message;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (!(node instanceof ObjectNode objectNode)) {
                return message;
            }
            if (!shouldInjectContinuationHint(objectNode, previousResponseId)) {
                return message;
            }
            JsonNode current = objectNode.get("previous_response_id");
            String expectedPreviousResponseId = previousResponseId.trim();
            if (current != null && current.isTextual() && !current.asText().isBlank()) {
                String currentPreviousResponseId = current.asText().trim();
                if (expectedPreviousResponseId.equals(currentPreviousResponseId)) {
                    return message;
                }
            }
            objectNode.put("previous_response_id", expectedPreviousResponseId);
            return new TextMessage(objectMapper.writeValueAsString(objectNode));
        } catch (Exception ex) {
            return message;
        }
    }

    public TextMessage normalizeContinuationTurn(
            TextMessage message,
            String expectedPreviousResponseId,
            JsonNode replayInput,
            String previousTurnPayload
    ) {
        if (message == null || message.getPayload() == null || message.getPayload().isBlank()) {
            return message;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (!(node instanceof ObjectNode objectNode)) {
                return message;
            }
            if (!"response.create".equals(stringField(objectNode.get("type")))) {
                return message;
            }
            String expected = expectedPreviousResponseId == null ? "" : expectedPreviousResponseId.trim();
            if (shouldInferFunctionCallOutputPreviousResponseId(objectNode, expected)) {
                objectNode.put("previous_response_id", expected);
            }
            return new TextMessage(objectMapper.writeValueAsString(objectNode));
        } catch (Exception ex) {
            return message;
        }
    }

    public boolean requiresFunctionCallOutputAnchor(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return false;
            }
            if (!"response.create".equals(stringField(objectNode.get("type")))) {
                return false;
            }
            if (!hasFunctionCallOutput(objectNode)) {
                return false;
            }
            return stringField(objectNode.get("previous_response_id")).isBlank()
                    && !hasToolCallContext(objectNode)
                    && !hasFunctionCallOutputMissingCallId(objectNode);
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean shouldRetryWithoutPreviousResponseId(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return false;
            }
            if (!"response.create".equals(stringField(objectNode.get("type")))) {
                return false;
            }
            if (hasFunctionCallOutput(objectNode)) {
                return false;
            }
            JsonNode previousResponseId = objectNode.get("previous_response_id");
            return previousResponseId != null
                    && previousResponseId.isTextual()
                    && !previousResponseId.asText().isBlank();
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean hasFunctionCallOutput(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node instanceof ObjectNode objectNode && hasFunctionCallOutput(objectNode);
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean isRecoverablePreviousResponseNotFound(String payload, boolean hasFunctionCallOutput) {
        if (hasFunctionCallOutput || payload == null || payload.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return false;
            }
            if (!"error".equals(stringField(objectNode.get("type")))) {
                return false;
            }
            JsonNode errorNode = objectNode.get("error");
            if (errorNode == null || !errorNode.isObject()) {
                return false;
            }
            String code = stringField(errorNode.get("code")).toLowerCase(Locale.ROOT);
            String errType = stringField(errorNode.get("type")).toLowerCase(Locale.ROOT);
            String errMessage = stringField(errorNode.get("message")).toLowerCase(Locale.ROOT);
            if ("previous_response_not_found".equals(code)) {
                return true;
            }
            if (code.contains("previous_response_not_found")) {
                return true;
            }
            if (errMessage.contains("previous_response_not_found")) {
                return true;
            }
            return errType.contains("invalid_request")
                    && errMessage.contains("previous response")
                    && errMessage.contains("not found");
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean isRecoverableInvalidEncryptedContent(String payload, boolean hasFunctionCallOutput) {
        if (hasFunctionCallOutput || payload == null || payload.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return false;
            }
            if (!"error".equals(stringField(objectNode.get("type")))) {
                return false;
            }
            JsonNode errorNode = objectNode.get("error");
            if (errorNode == null || !errorNode.isObject()) {
                return false;
            }
            String code = stringField(errorNode.get("code")).toLowerCase(Locale.ROOT);
            String errMessage = stringField(errorNode.get("message")).toLowerCase(Locale.ROOT);
            if ("invalid_encrypted_content".equals(code) || code.contains("invalid_encrypted_content")) {
                return true;
            }
            return errMessage.contains("invalid_encrypted_content")
                    || (errMessage.contains("encrypted") && errMessage.contains("content"));
        } catch (Exception ex) {
            return false;
        }
    }

    public JsonNode buildReplayInput(JsonNode previousReplayInput, String payload) {
        if (payload == null || payload.isBlank()) {
            return cloneReplayInput(previousReplayInput);
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return cloneReplayInput(previousReplayInput);
            }
            if (!"response.create".equals(stringField(objectNode.get("type")))) {
                return cloneReplayInput(previousReplayInput);
            }
            JsonNode inputNode = objectNode.get("input");
            if (inputNode == null || inputNode.isNull()) {
                return cloneReplayInput(previousReplayInput);
            }
            ArrayNode currentItems = normalizeReplayInputItems(inputNode);
            if (currentItems == null || currentItems.isEmpty()) {
                return cloneReplayInput(previousReplayInput);
            }
            if (stringField(objectNode.get("previous_response_id")).isBlank()) {
                return currentItems;
            }
            ArrayNode merged = objectMapper.createArrayNode();
            appendReplayItems(merged, previousReplayInput);
            appendReplayItems(merged, currentItems);
            return merged.isEmpty() ? currentItems : merged;
        } catch (Exception ex) {
            return cloneReplayInput(previousReplayInput);
        }
    }

    public TextMessage applyReplayInput(TextMessage message, JsonNode replayInput) {
        if (message == null || replayInput == null || replayInput.isNull()) {
            return message;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (!(node instanceof ObjectNode objectNode)) {
                return message;
            }
            if (!"response.create".equals(stringField(objectNode.get("type")))) {
                return message;
            }
            objectNode.set("input", cloneReplayInput(replayInput));
            return new TextMessage(objectMapper.writeValueAsString(objectNode));
        } catch (Exception ex) {
            return message;
        }
    }

    public TextMessage dropPreviousResponseId(TextMessage message) {
        if (message == null || message.getPayload() == null || message.getPayload().isBlank()) {
            return message;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (!(node instanceof ObjectNode objectNode)) {
                return message;
            }
            JsonNode previousResponseId = objectNode.get("previous_response_id");
            if (previousResponseId == null || !previousResponseId.isTextual() || previousResponseId.asText().isBlank()) {
                return message;
            }
            objectNode.remove("previous_response_id");
            return new TextMessage(objectMapper.writeValueAsString(objectNode));
        } catch (Exception ex) {
            return message;
        }
    }

    public TextMessage trimEncryptedReasoningItems(TextMessage message) {
        if (message == null || message.getPayload() == null || message.getPayload().isBlank()) {
            return message;
        }
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if (!(node instanceof ObjectNode objectNode)) {
                return message;
            }
            if (!trimEncryptedReasoningItems(objectNode)) {
                return message;
            }
            return new TextMessage(objectMapper.writeValueAsString(objectNode));
        } catch (Exception ex) {
            return message;
        }
    }

    public TextMessage rewriteUpstreamMessage(String payload, AdminAccountResponse account, String requestedModel) {
        if (payload == null || payload.isBlank() || account == null || requestedModel == null || requestedModel.isBlank()) {
            return new TextMessage(payload == null ? "" : payload);
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return new TextMessage(payload);
            }
            if (!eventMayContainModel(stringField(objectNode.get("type")))) {
                return new TextMessage(payload);
            }
            String mappedModel = responsesService.resolveMappedModel(account, requestedModel);
            if (mappedModel.equals(requestedModel)) {
                return new TextMessage(payload);
            }
            JsonNode modelNode = objectNode.get("model");
            if (modelNode == null || !modelNode.isTextual() || !mappedModel.equals(modelNode.asText().trim())) {
                return new TextMessage(payload);
            }
            objectNode.put("model", requestedModel);
            return new TextMessage(objectMapper.writeValueAsString(objectNode));
        } catch (Exception ex) {
            return new TextMessage(payload);
        }
    }

    public TextMessage buildBlockedEvent(String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event_id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        payload.put("type", "error");
        ObjectNode error = payload.putObject("error");
        error.put("type", "invalid_request_error");
        error.put("code", "policy_violation");
        error.put("message", message == null || message.isBlank()
                ? "openai fast policy blocked this request"
                : message.trim());
        return new TextMessage(payload.toString());
    }

    public WebSocketHttpHeaders buildUpstreamHeaders(WebSocketSession session, AdminAccountResponse account) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        session.getHandshakeHeaders().forEach((name, values) -> {
            String lower = normalizeHeaderName(name);
            if (!REQUEST_HEADER_ALLOWLIST.contains(lower) || CLIENT_REQUEST_HEADER_BLOCKLIST.contains(lower)) {
                return;
            }
            values.forEach(value -> headers.add(name, value));
        });
        String authToken = resolveAuthToken(account);
        if (authToken == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No upstream credentials available");
        }
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
        if (isOauthLikeType(account.type())) {
            headers.set(HttpHeaders.HOST, "chatgpt.com");
            String chatgptAccountId = responsesService.stringValue(account.credentials(), "chatgpt_account_id");
            if (chatgptAccountId != null) {
                headers.set("chatgpt-account-id", chatgptAccountId);
            }
            headers.set("originator", resolveUpstreamOriginator(session));
        }
        applySessionResolution(headers, session, account);
        applyTurnHeaders(headers, session);
        headers.set("OpenAI-Beta", OPENAI_WS_BETA_V2_VALUE);
        String customUserAgent = responsesService.stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            headers.set(HttpHeaders.USER_AGENT, customUserAgent);
        }
        if (isOauthLikeType(account.type()) && !isCodexCliRequest(headers.getFirst(HttpHeaders.USER_AGENT))) {
            headers.set(HttpHeaders.USER_AGENT, OPENAI_CODEX_USER_AGENT);
        }
        return headers;
    }

    public URI buildUpstreamUri(AdminAccountResponse account, WebSocketSession session) {
        String suffix = responsesService.resolveRequestSuffix(sessionPath(session));
        return URI.create(toWebSocketUrl(responsesService.buildResponsesUrl(account, suffix)));
    }

    private void applyTurnHeaders(WebSocketHttpHeaders headers, WebSocketSession session) {
        if (headers == null || session == null) {
            return;
        }
        copyHeaderIfPresent(headers, session, TURN_STATE_HEADER);
        copyHeaderIfPresent(headers, session, TURN_METADATA_HEADER);
    }

    private void applySessionResolution(WebSocketHttpHeaders headers, WebSocketSession session, AdminAccountResponse account) {
        if (headers == null || session == null) {
            return;
        }
        SessionHeaderResolution resolution = resolveSessionHeaders(session);
        if (resolution.sessionId() != null && !resolution.sessionId().isBlank()) {
            headers.set("session_id", resolution.sessionId().trim());
        }
        if (resolution.conversationId() != null && !resolution.conversationId().isBlank()) {
            headers.set("conversation_id", resolution.conversationId().trim());
        }
        if (isOauthLikeType(account == null ? null : account.type())) {
            isolateSessionHeaders(headers, session);
        }
    }

    public String resolveSubProtocol(WebSocketSession session) {
        String raw = session.getHandshakeHeaders().getFirst("Sec-WebSocket-Protocol");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private String sessionPath(WebSocketSession session) {
        URI uri = session.getUri();
        return uri == null ? "" : uri.getPath();
    }

    private String resolvePassthroughPolicyModel(WebSocketSession session, ObjectNode payload, AdminAccountResponse account) {
        String requestedModel = stringField(payload == null ? null : payload.get("model"));
        if (requestedModel.isBlank()) {
            requestedModel = resolveSessionModel(session);
        }
        if (requestedModel.isBlank()) {
            return "";
        }
        return responsesService.resolveMappedModel(account, requestedModel);
    }

    private void normalizeSessionUpdateModel(ObjectNode normalized, AdminAccountResponse account) {
        if (normalized == null || account == null) {
            return;
        }
        JsonNode sessionNode = normalized.get("session");
        if (!(sessionNode instanceof ObjectNode sessionObject)) {
            return;
        }
        JsonNode modelNode = sessionObject.get("model");
        if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            return;
        }
        String requestedModel = modelNode.asText().trim();
        String mappedModel = responsesService.resolveMappedModel(account, requestedModel);
        if (!mappedModel.equals(requestedModel)) {
            sessionObject.put("model", mappedModel);
        }
    }

    private void applyTurnMetadataClientMetadata(ObjectNode normalized, WebSocketSession session) {
        if (normalized == null || session == null) {
            return;
        }
        String turnMetadata = headerValue(session, TURN_METADATA_HEADER);
        if (turnMetadata == null || turnMetadata.isBlank()) {
            return;
        }
        JsonNode clientMetadataNode = normalized.get("client_metadata");
        ObjectNode clientMetadata;
        if (clientMetadataNode instanceof ObjectNode objectNode) {
            clientMetadata = objectNode;
        } else {
            clientMetadata = objectMapper.createObjectNode();
            normalized.set("client_metadata", clientMetadata);
        }
        JsonNode existing = clientMetadata.get(TURN_METADATA_HEADER);
        if (existing != null && existing.isTextual() && !existing.asText().isBlank()) {
            return;
        }
        clientMetadata.put(TURN_METADATA_HEADER, turnMetadata.trim());
    }

    public String resolveSessionModel(WebSocketSession session) {
        if (session == null) {
            return "";
        }
        Object value = session.getAttributes().get("gateway.openai.responses.ws.session_model");
        if (!(value instanceof String model)) {
            return "";
        }
        return model.trim();
    }

    public void captureSessionModel(WebSocketSession session, String payload) {
        if (session == null || payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!(node instanceof ObjectNode objectNode)) {
                return;
            }
            String eventType = stringField(objectNode.get("type"));
            String sessionModel = "";
            if ("session.update".equals(eventType)) {
                JsonNode sessionNode = objectNode.get("session");
                if (sessionNode != null && sessionNode.isObject()) {
                    sessionModel = stringField(sessionNode.get("model"));
                }
            } else if ("response.create".equals(eventType)) {
                sessionModel = stringField(objectNode.get("model"));
            }
            if (!sessionModel.isBlank()) {
                session.getAttributes().put("gateway.openai.responses.ws.session_model", sessionModel);
            }
            String promptCacheKey = stringField(objectNode.get("prompt_cache_key"));
            if (!promptCacheKey.isBlank()) {
                session.getAttributes().put("gateway.openai.responses.ws.prompt_cache_key", promptCacheKey);
            }
        } catch (Exception ignored) {
        }
    }

    private String toWebSocketUrl(String url) {
        if (url.startsWith("https://")) {
            return "wss://" + url.substring("https://".length());
        }
        if (url.startsWith("http://")) {
            return "ws://" + url.substring("http://".length());
        }
        return url;
    }

    private String stringField(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return "";
        }
        return node.asText().trim();
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

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private boolean isStoreRecoveryAllowed(AdminAccountResponse account) {
        if (account == null || account.extra() == null) {
            return false;
        }
        Object enabled = account.extra().get("openai_ws_allow_store_recovery");
        if (enabled instanceof Boolean flag) {
            return flag;
        }
        return enabled instanceof String text && "true".equalsIgnoreCase(text.trim());
    }

    private void isolateSessionHeaders(WebSocketHttpHeaders headers, WebSocketSession session) {
        if (headers == null || session == null) {
            return;
        }
        long apiKeyId = resolveApiKeyId(session);
        if (apiKeyId <= 0) {
            return;
        }
        String sessionId = headers.getFirst("session_id");
        if (sessionId != null && !sessionId.isBlank()) {
            headers.set("session_id", isolateSessionId(apiKeyId, sessionId));
        }
        String conversationId = headers.getFirst("conversation_id");
        if (conversationId != null && !conversationId.isBlank()) {
            headers.set("conversation_id", isolateSessionId(apiKeyId, conversationId));
        }
    }

    private SessionHeaderResolution resolveSessionHeaders(WebSocketSession session) {
        String sessionId = headerValue(session, "session_id");
        String conversationId = headerValue(session, "conversation_id");
        if ((sessionId == null || sessionId.isBlank()) && conversationId != null && !conversationId.isBlank()) {
            sessionId = conversationId.trim();
        }
        if (sessionId == null || sessionId.isBlank()) {
            String promptCacheKey = resolvePromptCacheKey(session);
            if (promptCacheKey != null && !promptCacheKey.isBlank()) {
                sessionId = promptCacheKey.trim();
            }
        }
        return new SessionHeaderResolution(sessionId, conversationId);
    }

    private String resolvePromptCacheKey(WebSocketSession session) {
        Object value = session == null ? null : session.getAttributes().get("gateway.openai.responses.ws.prompt_cache_key");
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    private void copyHeaderIfPresent(WebSocketHttpHeaders headers, WebSocketSession session, String name) {
        String value = headerValue(session, name);
        if (value != null && !value.isBlank()) {
            headers.set(name, value.trim());
        }
    }

    private String isolateSessionId(long apiKeyId, String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        return apiKeyId + ":" + normalized;
    }

    private long resolveApiKeyId(WebSocketSession session) {
        Object attr = session.getAttributes().get(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY);
        if (attr instanceof GatewayApiKeyPrincipal principal) {
            return principal.apiKeyId();
        }
        return 0L;
    }

    private String resolveUpstreamOriginator(WebSocketSession session) {
        String originator = headerValue(session, "originator");
        if (originator != null) {
            return originator;
        }
        return isOfficialCodexClient(session) ? "codex_cli_rs" : "opencode";
    }

    private boolean isOfficialCodexClient(WebSocketSession session) {
        return matchesOfficialCodexHeader(headerValue(session, HttpHeaders.USER_AGENT))
                || matchesOfficialCodexHeader(headerValue(session, "originator"));
    }

    private boolean matchesOfficialCodexHeader(String value) {
        String normalized = normalizeHeaderName(value);
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

    private boolean isCodexCliRequest(String userAgent) {
        String normalized = normalizeHeaderName(userAgent);
        return normalized.startsWith("codex_vscode/")
                || normalized.startsWith("codex_cli_rs/")
                || normalized.contains("codex_vscode/")
                || normalized.contains("codex_cli_rs/");
    }

    private String headerValue(WebSocketSession session, String name) {
        if (session == null || session.getHandshakeHeaders() == null || name == null || name.isBlank()) {
            return null;
        }
        String value = session.getHandshakeHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean looksLikeMessageId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("msg_")
                || normalized.startsWith("message_")
                || normalized.startsWith("item_")
                || normalized.startsWith("chatcmpl_");
    }

    private String normalizeHeaderName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean eventMayContainModel(String eventType) {
        String normalized = eventType == null ? "" : eventType.trim();
        return switch (normalized) {
            case "response.created",
                 "response.in_progress",
                 "response.completed",
                 "response.done",
                 "response.failed",
                 "response.incomplete",
                 "response.cancelled",
                 "response.canceled" -> true;
            default -> false;
        };
    }

    private boolean hasFunctionCallOutput(ObjectNode objectNode) {
        JsonNode input = objectNode.get("input");
        if (input == null || !input.isArray()) {
            return false;
        }
        for (JsonNode item : input) {
            if (item != null
                    && item.isObject()
                    && item.get("type") != null
                    && item.get("type").isTextual()
                    && "function_call_output".equals(item.get("type").asText().trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFunctionCallOutputMissingCallId(ObjectNode objectNode) {
        JsonNode input = objectNode.get("input");
        if (input == null || !input.isArray()) {
            return false;
        }
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode inputObject)) {
                continue;
            }
            if (!"function_call_output".equals(stringField(inputObject.get("type")))) {
                continue;
            }
            if (stringField(inputObject.get("call_id")).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolCallContext(ObjectNode objectNode) {
        JsonNode input = objectNode.get("input");
        if (input == null || !input.isArray()) {
            return false;
        }
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode inputObject)) {
                continue;
            }
            String type = stringField(inputObject.get("type"));
            if (!"tool_call".equals(type) && !"function_call".equals(type)) {
                continue;
            }
            if (!stringField(inputObject.get("call_id")).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode normalizeReplayInputItems(JsonNode inputNode) {
        if (inputNode == null || inputNode.isNull()) {
            return null;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        appendReplayItems(normalized, inputNode);
        return normalized;
    }

    private void appendReplayItems(ArrayNode target, JsonNode inputNode) {
        if (target == null || inputNode == null || inputNode.isNull()) {
            return;
        }
        if (inputNode.isArray()) {
            for (JsonNode item : inputNode) {
                target.add(item == null ? null : item.deepCopy());
            }
            return;
        }
        target.add(inputNode.deepCopy());
    }

    private JsonNode cloneReplayInput(JsonNode replayInput) {
        return replayInput == null ? null : replayInput.deepCopy();
    }

    private boolean shouldInferFunctionCallOutputPreviousResponseId(ObjectNode objectNode, String expectedPreviousResponseId) {
        if (objectNode == null || expectedPreviousResponseId == null || expectedPreviousResponseId.isBlank()) {
            return false;
        }
        if (!hasFunctionCallOutput(objectNode)) {
            return false;
        }
        if (!stringField(objectNode.get("previous_response_id")).isBlank()) {
            return false;
        }
        if (hasFunctionCallOutputMissingCallId(objectNode)) {
            return false;
        }
        return !hasToolCallContext(objectNode);
    }

    private boolean shouldInjectContinuationHint(ObjectNode objectNode, String previousResponseId) {
        if (objectNode == null || previousResponseId == null || previousResponseId.isBlank()) {
            return false;
        }
        if (!"response.create".equals(stringField(objectNode.get("type")))) {
            return false;
        }
        if (hasFunctionCallOutput(objectNode)) {
            return true;
        }
        JsonNode store = objectNode.get("store");
        if (store == null || !store.isBoolean() || store.asBoolean(true)) {
            return false;
        }
        JsonNode input = objectNode.get("input");
        return input != null && !input.isNull();
    }

    private boolean trimEncryptedReasoningItems(ObjectNode requestBody) {
        JsonNode inputNode = requestBody.get("input");
        if (inputNode == null || inputNode.isNull()) {
            return false;
        }
        if (inputNode instanceof ArrayNode inputItems) {
            ArrayNode retained = objectMapper.createArrayNode();
            boolean changed = false;
            for (JsonNode itemNode : inputItems) {
                SanitizedInputItem sanitized = sanitizeEncryptedReasoningInputItem(itemNode);
                changed |= sanitized.changed();
                if (sanitized.keep()) {
                    retained.add(sanitized.next());
                }
            }
            if (!changed) {
                return false;
            }
            if (retained.isEmpty()) {
                requestBody.remove("input");
                return true;
            }
            requestBody.set("input", retained);
            return true;
        }
        if (!(inputNode instanceof ObjectNode inputObject)) {
            return false;
        }
        SanitizedInputItem sanitized = sanitizeEncryptedReasoningInputItem(inputObject);
        if (!sanitized.changed()) {
            return false;
        }
        if (!sanitized.keep()) {
            requestBody.remove("input");
            return true;
        }
        requestBody.set("input", sanitized.next());
        return true;
    }

    private SanitizedInputItem sanitizeEncryptedReasoningInputItem(JsonNode itemNode) {
        if (!(itemNode instanceof ObjectNode inputObject)) {
            return new SanitizedInputItem(itemNode, false, true);
        }
        if (!"reasoning".equals(stringField(inputObject.get("type")))) {
            return new SanitizedInputItem(itemNode, false, true);
        }
        if (!inputObject.has("encrypted_content")) {
            return new SanitizedInputItem(itemNode, false, true);
        }
        ObjectNode sanitized = inputObject.deepCopy();
        sanitized.remove("encrypted_content");
        if (sanitized.size() == 1) {
            return new SanitizedInputItem(null, true, false);
        }
        return new SanitizedInputItem(sanitized, true, true);
    }

    private GatewayResponsesWebSocketCloseException closeError(CloseStatus status, String reason) {
        return new GatewayResponsesWebSocketCloseException(status, reason);
    }

    private GatewayResponsesWebSocketCloseException blockedError(String reason) {
        return new GatewayResponsesWebSocketCloseException(
                CloseStatus.POLICY_VIOLATION.withReason(reason),
                reason,
                buildBlockedEvent(reason)
        );
    }

    public static final class GatewayResponsesWebSocketCloseException extends RuntimeException {
        private final CloseStatus closeStatus;
        private final TextMessage clientEvent;

        public GatewayResponsesWebSocketCloseException(CloseStatus closeStatus, String reason) {
            this(closeStatus, reason, null);
        }

        public GatewayResponsesWebSocketCloseException(CloseStatus closeStatus, String reason, TextMessage clientEvent) {
            super(reason);
            this.closeStatus = closeStatus;
            this.clientEvent = clientEvent;
        }

        public CloseStatus closeStatus() {
            return closeStatus;
        }

        public TextMessage clientEvent() {
            return clientEvent;
        }
    }

    private record SessionHeaderResolution(String sessionId, String conversationId) {
    }

    private record SanitizedInputItem(JsonNode next, boolean changed, boolean keep) {
    }
}
