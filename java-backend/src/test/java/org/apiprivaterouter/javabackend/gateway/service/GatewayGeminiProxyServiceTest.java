package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayGeminiProxyServiceTest {

    private final GatewayGeminiProxyService service = new GatewayGeminiProxyService(
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            new UpstreamUrlGuard(new UrlAllowlistProperties(true, null, null, null, false, false))
    );

    @Test
    void antigravityOauthRouteCanBeHandled() {
        GatewayRuntimeContext runtimeContext = runtimeContext("antigravity", "oauth");

        assertTrue(service.canHandle(runtimeContext, true));
    }

    @Test
    void antigravityOauthAlsoParticipatesInMixedGeminiHandling() {
        GatewayRuntimeContext runtimeContext = runtimeContext("antigravity", "oauth");

        assertTrue(service.canHandle(runtimeContext, false));
    }

    @Test
    void antigravityServiceAccountIsStillRejected() {
        GatewayRuntimeContext runtimeContext = runtimeContext("antigravity", "service_account");

        assertFalse(service.canHandle(runtimeContext, true));
        assertFalse(service.canHandle(runtimeContext, false));
    }

    private GatewayRuntimeContext runtimeContext(String platform, String type) {
        GatewayAccountSummary account = new GatewayAccountSummary(
                1L,
                "acc",
                platform,
                type,
                "active",
                1,
                null,
                Map.of(),
                Map.of()
        );
        return new GatewayRuntimeContext(null, null, null, account);
    }
}
