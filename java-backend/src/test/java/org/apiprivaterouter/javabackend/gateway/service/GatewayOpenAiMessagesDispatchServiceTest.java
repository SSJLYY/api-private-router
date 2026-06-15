package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayGroupSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GatewayOpenAiMessagesDispatchServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayOpenAiFastPolicyService fastPolicyService = new GatewayOpenAiFastPolicyService(
            null,
            new JsonHelper(objectMapper)
    );
    private final UpstreamUrlGuard upstreamUrlGuard = new UpstreamUrlGuard(
            new UrlAllowlistProperties(true, null, null, null, false, false)
    );
    private final GatewayOpenAiResponsesService responsesService = new GatewayOpenAiResponsesService(
            null,
            null,
            new GatewayOpenAiAccountRoutingPolicy(),
            fastPolicyService,
            objectMapper,
            upstreamUrlGuard
    );
    private final GatewayOpenAiMessagesDispatchService service = new GatewayOpenAiMessagesDispatchService(
            null,
            null,
            responsesService,
            fastPolicyService,
            null,
            objectMapper
    );

    @Test
    void setupTokenDispatchMappingNormalizesOauthLikeUpstreamModel() throws Exception {
        AdminAccountResponse account = account("setup-token", Map.of(), Map.of());
        GatewayGroupSummary group = new GatewayGroupSummary(
                1L,
                "g",
                "openai",
                "subscription",
                null,
                null,
                null,
                true,
                false,
                "",
                new GatewayGroupSummary.MessagesDispatchModelConfig(
                        "",
                        "",
                        "",
                        Map.of("claude-sonnet-4-5", "gpt-5.1-codex")
                )
        );

        ObjectNode responsesRequest = toResponsesRequest(
                baseAnthropicRequest("claude-sonnet-4-5"),
                account,
                group,
                new MockHttpServletRequest(),
                false
        );

        assertEquals("gpt-5.3-codex", responsesRequest.path("model").asText());
    }

    @Test
    void setupTokenFastModeHeaderIsTranslatedAndFiltered() throws Exception {
        AdminAccountResponse account = account("setup-token", Map.of(), Map.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("anthropic-beta", "oauth-2025-04-20, fast-mode-2026-02-01, other-beta");

        ObjectNode responsesRequest = toResponsesRequest(
                baseAnthropicRequest("claude-sonnet-4-5"),
                account,
                null,
                request,
                false
        );

        assertFalse(responsesRequest.has("service_tier"));
    }

    @Test
    void openAiCompatReasoningSuffixSetsEffortAndNormalizesModel() throws Exception {
        AdminAccountResponse account = account("setup-token", Map.of(), Map.of());
        ObjectNode anthropicRequest = baseAnthropicRequest("gpt-5.1-codex-high");

        normalizeAnthropicRequest(anthropicRequest, account);
        ObjectNode responsesRequest = toResponsesRequest(
                anthropicRequest,
                account,
                null,
                new MockHttpServletRequest(),
                false
        );

        assertEquals("gpt-5.3-codex", anthropicRequest.path("model").asText());
        assertEquals("high", anthropicRequest.path("output_config").path("effort").asText());
        assertEquals("gpt-5.3-codex", responsesRequest.path("model").asText());
        assertEquals("high", responsesRequest.path("reasoning").path("effort").asText());
    }

    private ObjectNode baseAnthropicRequest(String model) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("stream", false);
        request.putArray("messages")
                .add(objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", "hello"));
        return request;
    }

    private void normalizeAnthropicRequest(ObjectNode anthropicRequest, AdminAccountResponse account) throws Exception {
        Method method = GatewayOpenAiMessagesDispatchService.class.getDeclaredMethod(
                "applyOpenAiCompatModelNormalization",
                ObjectNode.class,
                AdminAccountResponse.class
        );
        method.setAccessible(true);
        method.invoke(service, anthropicRequest, account);
    }

    private ObjectNode toResponsesRequest(
            ObjectNode anthropicRequest,
            AdminAccountResponse account,
            GatewayGroupSummary group,
            HttpServletRequest request,
            boolean stream
    ) throws Exception {
        Method method = GatewayOpenAiMessagesDispatchService.class.getDeclaredMethod(
                "toResponsesRequest",
                ObjectNode.class,
                AdminAccountResponse.class,
                GatewayGroupSummary.class,
                HttpServletRequest.class,
                boolean.class
        );
        method.setAccessible(true);
        return (ObjectNode) method.invoke(service, anthropicRequest, account, group, request, stream);
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
}
