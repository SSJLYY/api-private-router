package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayApiKeyRuntimeView;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayGroupSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayOpenAiResponsesServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayOpenAiFastPolicyService fastPolicyService = new GatewayOpenAiFastPolicyService(
            null,
            new JsonHelper(objectMapper)
    );
    private final UpstreamUrlGuard upstreamUrlGuard = new UpstreamUrlGuard(
            new UrlAllowlistProperties(true, null, null, null, false, false)
    );
    private final GatewayOpenAiResponsesService service = new GatewayOpenAiResponsesService(
            null,
            null,
            new GatewayOpenAiAccountRoutingPolicy(),
            fastPolicyService,
            objectMapper,
            upstreamUrlGuard
    );

    @Test
    void compactMappingChainsBaseAndCompactMappings() {
        AdminAccountResponse account = account(
                "apikey",
                Map.of(
                        "model_mapping", Map.of("gpt-5", "mapped-base"),
                        "compact_model_mapping", Map.of("mapped-base", "mapped-compact")
                ),
                Map.of()
        );

        assertEquals("mapped-compact", service.resolveMappedModelForRequest(account, "gpt-5", true));
    }

    @Test
    void buildResponsesUrlRejectsLoopbackBaseUrl() {
        AdminAccountResponse account = account("apikey", Map.of("base_url", "https://127.0.0.1:8080"), Map.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.buildResponsesUrl(account, "")
        );

        assertEquals("invalid base_url: host is not allowed", ex.getMessage());
    }

    @Test
    void passthroughRequestDoesNotInjectDefaultInstructions() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, true);

        byte[] payload = objectMapper.writeValueAsBytes(objectMapper.createObjectNode()
                .put("model", "gpt-5")
                .put("stream", false));

        ObjectNode normalized = parsePreparedPayload(payload, account, false, true, runtimeContext, false);

        assertFalse(normalized.has("instructions"));
    }

    @Test
    void emptyBase64InputImageIsRemoved() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        request.putArray("input")
                .add(objectMapper.createObjectNode()
                        .put("type", "input_image")
                        .put("image_url", "data:image/png;base64,   "))
                .add(objectMapper.createObjectNode()
                        .put("type", "input_text")
                        .put("text", "hello"));

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertTrue(normalized.has("input"));
        assertEquals(1, normalized.withArray("input").size());
        assertEquals("input_text", normalized.withArray("input").get(0).path("type").asText());
    }

    @Test
    void imageGenerationRequestRequiresGroupPermission() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(false, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        request.putArray("tools")
                .add(objectMapper.createObjectNode().put("type", "image_generation"));

        OpenAiApiErrorExceptionWrapper thrown = assertThrows(
                OpenAiApiErrorExceptionWrapper.class,
                () -> parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false)
        );
        assertEquals(403, thrown.status());
    }

    @Test
    void apikeyRequestDropsMaxTokenFieldsForNonCodexClient() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        request.put("max_output_tokens", 128);
        request.put("max_completion_tokens", 256);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertFalse(normalized.has("max_output_tokens"));
        assertFalse(normalized.has("max_completion_tokens"));
    }

    @Test
    void officialCodexClientKeepsMaxTokenFields() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        request.put("max_output_tokens", 128);
        request.put("max_completion_tokens", 256);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, true);

        assertEquals(128, normalized.path("max_output_tokens").asInt());
        assertEquals(256, normalized.path("max_completion_tokens").asInt());
    }

    @Test
    void reasoningMinimalNormalizesToNone() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        request.set("reasoning", objectMapper.createObjectNode().put("effort", "minimal"));

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertEquals("none", normalized.path("reasoning").path("effort").asText());
    }

    @Test
    void oauthCodexModelNormalizesForUpstream() throws Exception {
        AdminAccountResponse account = account("oauth", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.1-codex");
        request.put("stream", false);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertEquals("gpt-5.3-codex", normalized.path("model").asText());
    }

    @Test
    void setupTokenUsesOauthNormalizationForUpstreamModel() throws Exception {
        AdminAccountResponse account = account("setup-token", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.1-codex");
        request.put("stream", false);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertEquals("gpt-5.3-codex", normalized.path("model").asText());
    }

    @Test
    void setupTokenExtractsSystemMessagesIntoInstructions() throws Exception {
        AdminAccountResponse account = account("setup-token", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        ObjectNode systemMessage = objectMapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.putArray("content")
                .add(objectMapper.createObjectNode().put("type", "input_text").put("text", "system rules"));
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.putArray("content")
                .add(objectMapper.createObjectNode().put("type", "input_text").put("text", "hello"));
        request.putArray("input")
                .add(systemMessage)
                .add(userMessage);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertEquals("system rules", normalized.path("instructions").asText());
        assertEquals(1, normalized.withArray("input").size());
        assertEquals("user", normalized.withArray("input").get(0).path("role").asText());
    }

    @Test
    void compactMappingStillWinsOverOauthModelNormalization() throws Exception {
        AdminAccountResponse account = account(
                "oauth",
                Map.of(
                        "model_mapping", Map.of("gpt-5", "gpt-5.1-codex"),
                        "compact_model_mapping", Map.of("gpt-5.1-codex", "custom-compact")
                ),
                Map.of()
        );
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        ObjectNode systemMessage = objectMapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.putArray("content")
                .add(objectMapper.createObjectNode().put("type", "input_text").put("text", "sys"));
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.putArray("content")
                .add(objectMapper.createObjectNode().put("type", "input_text").put("text", "hi"));
        request.putArray("input")
                .add(systemMessage)
                .add(userMessage);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, true, false, runtimeContext, false);

        assertEquals("custom-compact", normalized.path("model").asText());
    }

    @Test
    void oldGptModelDropsVerbosity() throws Exception {
        AdminAccountResponse account = account("apikey", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.2");
        request.put("stream", false);
        request.set("text", objectMapper.createObjectNode().put("verbosity", "high"));

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertFalse(normalized.has("text"));
    }

    @Test
    void oauthNonPassthroughExtractsSystemMessagesIntoInstructions() throws Exception {
        AdminAccountResponse account = account("oauth", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, false);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5");
        request.put("stream", false);
        ObjectNode systemMessage = objectMapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.putArray("content")
                .add(objectMapper.createObjectNode().put("type", "input_text").put("text", "system rules"));
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.putArray("content")
                .add(objectMapper.createObjectNode().put("type", "input_text").put("text", "hello"));
        request.putArray("input")
                .add(systemMessage)
                .add(userMessage);

        ObjectNode normalized = parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, false, runtimeContext, false);

        assertEquals("system rules", normalized.path("instructions").asText());
        assertEquals(1, normalized.withArray("input").size());
        assertEquals("user", normalized.withArray("input").get(0).path("role").asText());
    }

    @Test
    void oauthPassthroughCodexRequiresInstructionsForNonOfficialClient() throws Exception {
        AdminAccountResponse account = account("oauth", Map.of(), Map.of());
        GatewayRuntimeContext runtimeContext = runtimeContext(true, true);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.3-codex");
        request.put("stream", true);

        OpenAiApiErrorExceptionWrapper thrown = assertThrows(
                OpenAiApiErrorExceptionWrapper.class,
                () -> parsePreparedPayload(objectMapper.writeValueAsBytes(request), account, false, true, runtimeContext, false)
        );

        assertEquals(403, thrown.status());
        assertEquals("forbidden_error", thrown.errorType());
    }

    private ObjectNode parsePreparedPayload(
            byte[] payload,
            AdminAccountResponse account,
            boolean compactRequest,
            boolean passthrough,
            GatewayRuntimeContext runtimeContext,
            boolean officialCodexClient
    ) throws Exception {
        try {
            var method = GatewayOpenAiResponsesService.class.getDeclaredMethod(
                    "preparePayload",
                    byte[].class,
                    AdminAccountResponse.class,
                    boolean.class,
                    boolean.class,
                    GatewayRuntimeContext.class,
                    boolean.class
            );
            method.setAccessible(true);
            Object prepared = method.invoke(service, payload, account, compactRequest, passthrough, runtimeContext, officialCodexClient);
            var bodyMethod = prepared.getClass().getDeclaredMethod("body");
            bodyMethod.setAccessible(true);
            byte[] body = (byte[]) bodyMethod.invoke(prepared);
            return (ObjectNode) objectMapper.readTree(body);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException apiError) {
                throw new OpenAiApiErrorExceptionWrapper(apiError.getStatus(), apiError.getErrorType(), apiError.getMessage());
            }
            throw ex;
        }
    }

    private AdminAccountResponse account(String type, Map<String, Object> credentials, Map<String, Object> extra) {
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
                    case "extra" -> args[i] = extra;
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
            throw new IllegalStateException("failed to build AdminAccountResponse fixture", ex);
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

    private GatewayRuntimeContext runtimeContext(boolean allowImageGeneration, boolean passthrough) {
        GatewayGroupSummary group = new GatewayGroupSummary(
                1L,
                "g",
                "openai",
                "subscription",
                null,
                null,
                null,
                allowImageGeneration,
                false,
                null,
                null
        );
        GatewayApiKeyRuntimeView apiKey = new GatewayApiKeyRuntimeView(
                1L, 1L, "k", "n", "active", 1L,
                0, 0, null,
                0, 0, 0, 0, 0, 0,
                null, null, null, null, null, null,
                group
        );
        GatewayAccountSummary account = new GatewayAccountSummary(
                1L,
                "acc",
                "openai",
                "apikey",
                "active",
                1,
                null,
                Map.of(),
                passthrough ? Map.of("openai_passthrough", true) : Map.of()
        );
        return new GatewayRuntimeContext(apiKey, null, null, account);
    }

    private static final class OpenAiApiErrorExceptionWrapper extends RuntimeException {
        private final int status;
        private final String errorType;
        private final String detailMessage;

        private OpenAiApiErrorExceptionWrapper(int status, String errorType, String detailMessage) {
            super(detailMessage);
            this.status = status;
            this.errorType = errorType;
            this.detailMessage = detailMessage;
        }

        private int status() {
            return status;
        }

        private String errorType() {
            return errorType;
        }

        @Override
        public String getMessage() {
            return detailMessage;
        }
    }
}
