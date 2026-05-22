package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayOpenAiFastPolicyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayOpenAiFastPolicyService service = new GatewayOpenAiFastPolicyService(
            null,
            new JsonHelper(objectMapper)
    );

    @Test
    void responseCreatePriorityTierIsFilteredByDefault() {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "response.create");
        frame.put("model", "gpt-5");
        frame.put("service_tier", "fast");

        GatewayOpenAiFastPolicyService.FastPolicyApplyResult result =
                service.applyToResponseCreateFrame(account("apikey"), "gpt-5", frame);

        assertEquals(GatewayOpenAiFastPolicyService.Action.FILTER, result.action());
        assertNotNull(result.payload());
        assertFalse(result.payload().has("service_tier"));
    }

    @Test
    void nonResponseCreateFramePassesThroughUntouched() {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "session.update");
        frame.putObject("session").put("model", "gpt-5");
        frame.put("service_tier", "fast");

        GatewayOpenAiFastPolicyService.FastPolicyApplyResult result =
                service.applyToResponseCreateFrame(account("apikey"), "gpt-5", frame);

        assertEquals(GatewayOpenAiFastPolicyService.Action.PASS, result.action());
        assertTrue(result.payload().has("service_tier"));
    }

    @Test
    void requestBodyPriorityTierIsFilteredByDefault() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5");
        body.put("service_tier", "priority");

        GatewayOpenAiFastPolicyService.FastPolicyApplyResult result =
                service.applyToRequestBody(account("oauth"), "gpt-5", body, "priority");

        assertEquals(GatewayOpenAiFastPolicyService.Action.FILTER, result.action());
        assertFalse(result.payload().has("service_tier"));
    }

    @Test
    void setupTokenUsesOauthScopeForFastPolicy() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5");
        body.put("service_tier", "priority");

        GatewayOpenAiFastPolicyService.FastPolicyApplyResult result =
                service.applyToRequestBody(account("setup-token"), "gpt-5", body, "priority");

        assertEquals(GatewayOpenAiFastPolicyService.Action.FILTER, result.action());
        assertFalse(result.payload().has("service_tier"));
    }

    @Test
    void anthropicBedrockUsesBedrockScopeInsteadOfApiKeyScope() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5");
        body.put("service_tier", "priority");

        GatewayOpenAiFastPolicyService.FastPolicyApplyResult result =
                service.applyToRequestBody(account("anthropic", "bedrock"), "gpt-5", body, "priority");

        assertEquals(GatewayOpenAiFastPolicyService.Action.FILTER, result.action());
        assertFalse(result.payload().has("service_tier"));
    }

    @Test
    void buildBlockedEventUsesOpenAiRealtimeErrorShape() throws Exception {
        GatewayOpenAiResponsesWebSocketService wsService = new GatewayOpenAiResponsesWebSocketService(
                null,
                new GatewayOpenAiResponsesService(
                        null,
                        null,
                        null,
                        service,
                        objectMapper,
                        new UpstreamUrlGuard(new UrlAllowlistProperties(true, null, null, null, false, false))
                ),
                service,
                objectMapper
        );

        ObjectNode event = (ObjectNode) objectMapper.readTree(wsService.buildBlockedEvent("blocked").getPayload());

        assertEquals("error", event.path("type").asText());
        assertEquals("policy_violation", event.path("error").path("code").asText());
        assertEquals("blocked", event.path("error").path("message").asText());
        assertTrue(event.path("event_id").asText().startsWith("evt_"));
    }

    private AdminAccountResponse account(String type) {
        return account("openai", type);
    }

    private AdminAccountResponse account(String platform, String type) {
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
                    case "platform" -> args[i] = platform;
                    case "type" -> args[i] = type;
                    case "credentials" -> args[i] = Map.of();
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
}
