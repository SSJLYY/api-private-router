package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayOpenAiResponsesWebSocketServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayOpenAiFastPolicyService fastPolicyService = new GatewayOpenAiFastPolicyService(
            null,
            new JsonHelper(objectMapper)
    );
    private final UpstreamUrlGuard upstreamUrlGuard = new UpstreamUrlGuard(
            new UrlAllowlistProperties(true, null, null, null, false, false)
    );
    private final GatewayOpenAiResponsesWebSocketService service = new GatewayOpenAiResponsesWebSocketService(
            null,
            new GatewayOpenAiResponsesService(null, null, null, fastPolicyService, objectMapper, upstreamUrlGuard),
            fastPolicyService,
            objectMapper
    );

    @Test
    void trimEncryptedReasoningItemsRemovesEncryptedReasoningEntry() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "response.create");
        payload.put("model", "gpt-5");
        payload.putArray("input")
                .add(objectMapper.createObjectNode()
                        .put("type", "reasoning")
                        .put("encrypted_content", "secret"))
                .add(objectMapper.createObjectNode()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", "hello"));

        TextMessage trimmed = service.trimEncryptedReasoningItems(new TextMessage(objectMapper.writeValueAsString(payload)));
        ObjectNode normalized = (ObjectNode) objectMapper.readTree(trimmed.getPayload());

        assertTrue(normalized.has("input"));
        assertEquals(1, normalized.withArray("input").size());
        assertEquals("message", normalized.withArray("input").get(0).path("type").asText());
    }

    @Test
    void trimEncryptedReasoningItemsKeepsReasoningWithoutEncryptedContent() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "response.create");
        payload.put("model", "gpt-5");
        payload.putArray("input")
                .add(objectMapper.createObjectNode()
                        .put("type", "reasoning")
                        .put("summary", "ok"));

        TextMessage trimmed = service.trimEncryptedReasoningItems(new TextMessage(objectMapper.writeValueAsString(payload)));

        assertEquals(objectMapper.writeValueAsString(payload), trimmed.getPayload());
    }

    @Test
    void trimEncryptedReasoningItemsDropsOnlyEncryptedContentFieldWhenReasoningHasOtherFields() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "response.create");
        payload.put("model", "gpt-5");
        payload.putArray("input")
                .add(objectMapper.createObjectNode()
                        .put("type", "reasoning")
                        .put("id", "rs_123")
                        .put("encrypted_content", "secret"));

        TextMessage trimmed = service.trimEncryptedReasoningItems(new TextMessage(objectMapper.writeValueAsString(payload)));
        ObjectNode normalized = (ObjectNode) objectMapper.readTree(trimmed.getPayload());

        assertTrue(normalized.has("input"));
        assertEquals(1, normalized.withArray("input").size());
        assertFalse(normalized.withArray("input").get(0).has("encrypted_content"));
        assertEquals("rs_123", normalized.withArray("input").get(0).path("id").asText());
    }

    @Test
    void prepareClientMessageReusesSessionUpdatedModelAndStillAppliesFastPolicy() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession();
        AdminAccountResponse account = account("apikey", Map.of(
                "model_mapping", Map.of("custom-original-model", "gpt-5.5")
        ));

        TextMessage sessionUpdate = service.prepareClientMessage(
                session,
                "{\"type\":\"session.update\",\"session\":{\"model\":\"custom-original-model\"}}",
                account,
                true
        );
        service.captureSessionModel(session, sessionUpdate.getPayload());

        TextMessage create = service.prepareClientMessage(
                session,
                "{\"type\":\"response.create\",\"service_tier\":\"priority\"}",
                account,
                false
        );

        ObjectNode normalized = (ObjectNode) objectMapper.readTree(create.getPayload());
        assertEquals("gpt-5.5", normalized.path("model").asText());
        assertFalse(normalized.has("service_tier"));
        assertTrue(normalized.path("stream").asBoolean());
    }

    @Test
    void preparePassthroughClientMessageUsesCapturedSessionModelForPolicy() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession();
        AdminAccountResponse account = account("apikey", Map.of(
                "model_mapping", Map.of("custom-original-model", "gpt-5.5")
        ));

        TextMessage sessionUpdate = service.prepareClientMessage(
                session,
                "{\"type\":\"session.update\",\"session\":{\"model\":\"custom-original-model\"}}",
                account,
                true
        );
        service.captureSessionModel(session, sessionUpdate.getPayload());

        TextMessage passthrough = service.preparePassthroughClientMessage(
                session,
                "{\"type\":\"response.create\",\"service_tier\":\"priority\"}",
                account
        );

        ObjectNode normalized = (ObjectNode) objectMapper.readTree(passthrough.getPayload());
        assertFalse(normalized.has("service_tier"));
        assertFalse(normalized.has("model"));
    }

    @Test
    void preparePassthroughClientMessageRejectsResponseAppend() {
        TestWebSocketSession session = new TestWebSocketSession();
        AdminAccountResponse account = account("apikey", Map.of());

        GatewayOpenAiResponsesWebSocketService.GatewayResponsesWebSocketCloseException ex = assertThrows(
                GatewayOpenAiResponsesWebSocketService.GatewayResponsesWebSocketCloseException.class,
                () -> service.preparePassthroughClientMessage(
                        session,
                        "{\"type\":\"response.append\"}",
                        account
                )
        );

        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), ex.closeStatus().getCode());
    }

    @Test
    void applyContinuationHintsInjectsPreviousResponseIdForFunctionCallOutput() throws Exception {
        TextMessage message = new TextMessage("""
                {"type":"response.create","input":[{"type":"function_call_output","call_id":"call_1","output":"ok"}]}
                """);

        TextMessage continued = service.applyContinuationHints(message, "resp_prev_1");
        ObjectNode normalized = (ObjectNode) objectMapper.readTree(continued.getPayload());

        assertEquals("resp_prev_1", normalized.path("previous_response_id").asText());
    }

    @Test
    void applyContinuationHintsInjectsPreviousResponseIdForStoreDisabledFollowupInput() throws Exception {
        TextMessage message = new TextMessage("""
                {"type":"response.create","store":false,"input":[{"type":"message","role":"user","content":"continue"}]}
                """);

        TextMessage continued = service.applyContinuationHints(message, "resp_prev_2");
        ObjectNode normalized = (ObjectNode) objectMapper.readTree(continued.getPayload());

        assertEquals("resp_prev_2", normalized.path("previous_response_id").asText());
    }

    @Test
    void normalizeContinuationTurnInfersPreviousResponseIdForStoreDisabledFunctionCallOutput() throws Exception {
        TextMessage normalized = service.normalizeContinuationTurn(
                new TextMessage("""
                        {"type":"response.create","store":false,"input":[{"type":"function_call_output","call_id":"call_1","output":"ok"}]}
                        """),
                "resp_prev_fc_1",
                null,
                null
        );

        ObjectNode payload = (ObjectNode) objectMapper.readTree(normalized.getPayload());
        assertEquals("resp_prev_fc_1", payload.path("previous_response_id").asText());
    }

    @Test
    void requiresFunctionCallOutputAnchorOnlyWhenClientCannotSelfAnchor() {
        assertTrue(service.requiresFunctionCallOutputAnchor(
                "{\"type\":\"response.create\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"
        ));
        assertFalse(service.requiresFunctionCallOutputAnchor(
                "{\"type\":\"response.create\",\"previous_response_id\":\"resp_prev_6\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"
        ));
        assertFalse(service.requiresFunctionCallOutputAnchor(
                "{\"type\":\"response.create\",\"input\":[{\"type\":\"function_call\",\"call_id\":\"call_1\"},{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"
        ));
        assertFalse(service.requiresFunctionCallOutputAnchor(
                "{\"type\":\"response.create\",\"input\":[{\"type\":\"function_call_output\",\"output\":\"ok\"}]}"
        ));
    }

    @Test
    void shouldRetryWithoutPreviousResponseIdAllowsPlainResponseCreateOnly() {
        assertTrue(service.shouldRetryWithoutPreviousResponseId(
                "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_prev_3\"}"
        ));
        assertFalse(service.shouldRetryWithoutPreviousResponseId(
                "{\"type\":\"response.create\",\"previous_response_id\":\"resp_prev_3\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"
        ));
        assertFalse(service.shouldRetryWithoutPreviousResponseId(
                "{\"type\":\"session.update\",\"previous_response_id\":\"resp_prev_3\"}"
        ));
    }

    @Test
    void buildReplayInputMergesPreviousAndCurrentInputForIncrementalContinuation() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                [{"type":"message","role":"user","content":"first"}]
                """);

        JsonNode replay = service.buildReplayInput(
                previous,
                "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_prev_4\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"second\"}]}"
        );

        assertTrue(replay.isArray());
        assertEquals(2, replay.size());
        assertEquals("first", replay.get(0).path("content").asText());
        assertEquals("second", replay.get(1).path("content").asText());
    }

    @Test
    void applyReplayInputReplacesInputSequence() throws Exception {
        JsonNode replay = objectMapper.readTree("""
                [{"type":"message","role":"user","content":"replayed"}]
                """);

        TextMessage rebuilt = service.applyReplayInput(
                new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\"}"),
                replay
        );

        ObjectNode normalized = (ObjectNode) objectMapper.readTree(rebuilt.getPayload());
        assertEquals("replayed", normalized.path("input").get(0).path("content").asText());
    }

    @Test
    void prepareClientMessageRejectsMessageIdAsPreviousResponseId() {
        TestWebSocketSession session = new TestWebSocketSession();
        AdminAccountResponse account = account("apikey", Map.of());

        GatewayOpenAiResponsesWebSocketService.GatewayResponsesWebSocketCloseException ex = assertThrows(
                GatewayOpenAiResponsesWebSocketService.GatewayResponsesWebSocketCloseException.class,
                () -> service.prepareClientMessage(
                        session,
                        "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"msg_123\"}",
                        account,
                        true
                )
        );

        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), ex.closeStatus().getCode());
    }

    @Test
    void setupTokenDefaultsStoreFalseLikeOauth() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession();
        AdminAccountResponse account = account("setup-token", Map.of("access_token", "tok"));

        TextMessage prepared = service.prepareClientMessage(
                session,
                "{\"type\":\"response.create\",\"model\":\"gpt-5\"}",
                account,
                true
        );

        ObjectNode normalized = (ObjectNode) objectMapper.readTree(prepared.getPayload());
        assertFalse(normalized.path("store").asBoolean(true));
    }

    @Test
    void setupTokenBuildsOauthLikeUpstreamHeaders() {
        TestWebSocketSession session = new TestWebSocketSession();
        session.handshakeHeaders().set("user-agent", "custom-client/1.0");
        session.getAttributes().put("gateway.openai.responses.ws.prompt_cache_key", "pcache-1");
        AdminAccountResponse account = account("setup-token", Map.of(
                "access_token", "tok-1",
                "chatgpt_account_id", "acct-1"
        ));

        var headers = service.buildUpstreamHeaders(session, account);

        assertEquals("Bearer tok-1", headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals("chatgpt.com", headers.getFirst(HttpHeaders.HOST));
        assertEquals("acct-1", headers.getFirst("chatgpt-account-id"));
        assertEquals("opencode", headers.getFirst("originator"));
        assertEquals("codex_cli_rs/0.125.0", headers.getFirst(HttpHeaders.USER_AGENT));
        assertEquals("responses_websockets=2026-02-06", headers.getFirst("OpenAI-Beta"));
    }

    private AdminAccountResponse account(String type, Map<String, Object> credentials) {
        try {
            RecordComponent[] components = AdminAccountResponse.class.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                args[i] = defaultValue(component.getType());
                switch (component.getName()) {
                    case "id" -> args[i] = 1L;
                    case "name" -> args[i] = "acc";
                    case "platform" -> args[i] = "openai";
                    case "type" -> args[i] = type;
                    case "credentials" -> args[i] = credentials;
                    case "extra" -> args[i] = Map.of();
                    case "concurrency" -> args[i] = 1;
                    case "priority" -> args[i] = 1;
                    case "rate_multiplier" -> args[i] = 1.0d;
                    case "status" -> args[i] = "active";
                    case "group_ids" -> args[i] = List.<Long>of();
                    case "groups" -> args[i] = List.of();
                    default -> {
                    }
                }
            }
            Constructor<AdminAccountResponse> constructor = AdminAccountResponse.class.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(args);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        throw new IllegalArgumentException("unsupported primitive type: " + type);
    }

    private static final class TestWebSocketSession implements WebSocketSession {
        private final Map<String, Object> attributes = new HashMap<>();
        private final HttpHeaders handshakeHeaders = new HttpHeaders();

        @Override
        public String getId() {
            return "ws-test";
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/v1/responses");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return handshakeHeaders;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return Collections.emptyList();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void close(CloseStatus status) throws IOException {
        }

        private HttpHeaders handshakeHeaders() {
            return handshakeHeaders;
        }
    }
}
