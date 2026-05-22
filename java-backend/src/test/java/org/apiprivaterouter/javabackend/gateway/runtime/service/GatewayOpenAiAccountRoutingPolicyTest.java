package org.apiprivaterouter.javabackend.gateway.runtime.service;

import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayOpenAiAccountRoutingPolicyTest {

    private final GatewayOpenAiAccountRoutingPolicy policy = new GatewayOpenAiAccountRoutingPolicy();

    @Test
    void responsesWebSocketSupportsCtxPoolMode() {
        GatewayRuntimeContext runtimeContext = runtimeContext("apikey", Map.of(
                "responses_websockets_v2_mode", "ctx_pool"
        ));

        assertTrue(policy.canHandleResponsesWebSocket(runtimeContext));
    }

    @Test
    void responsesWebSocketSupportsPassthroughMode() {
        GatewayRuntimeContext runtimeContext = runtimeContext("oauth", Map.of(
                "responses_websockets_v2_mode", "passthrough",
                "responses_websockets_v2_enabled", true
        ));

        assertTrue(policy.canHandleResponsesWebSocket(runtimeContext));
    }

    @Test
    void setupTokenCanHandleResponsesHttp() {
        GatewayRuntimeContext runtimeContext = runtimeContext("setup-token", Map.of());

        assertFalse(policy.cannotHandleResponsesHttp(runtimeContext));
        assertTrue(policy.canHandleResponsesHttp(runtimeContext));
    }

    @Test
    void setupTokenSupportsOauthWebSocketFlags() {
        GatewayRuntimeContext runtimeContext = runtimeContext("setup-token", Map.of(
                "openai_oauth_responses_websockets_v2_enabled", true
        ));

        assertTrue(policy.canHandleResponsesWebSocket(runtimeContext));
    }

    private GatewayRuntimeContext runtimeContext(String type, Map<String, Object> extra) {
        GatewayAccountSummary account = new GatewayAccountSummary(
                1L,
                "acc",
                "openai",
                type,
                "active",
                1,
                null,
                Map.of(),
                extra
        );
        return new GatewayRuntimeContext(null, null, null, account);
    }
}
