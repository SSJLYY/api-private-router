package org.apiprivaterouter.javabackend.gateway.websocket;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.security.GatewayRequestAuthSupport;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ModerationApiKeyContext;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.repository.GatewayApiKeyRepository;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayResponsesHandshakeInterceptorTest {

    @Test
    void copiesExistingPrincipalFromServletRequestAttribute() {
        GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
        GatewayResponsesHandshakeInterceptor interceptor = new GatewayResponsesHandshakeInterceptor(
                new GatewayRequestAuthSupport(),
                repository
        );
        GatewayApiKeyPrincipal principal = new GatewayApiKeyPrincipal(
                11L,
                "demo",
                "active",
                12L,
                "demo@api-private-router.local",
                "active",
                5,
                "user",
                13L,
                "openai",
                "openai",
                "standard",
                false,
                true,
                false
        );
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRequestURI("/v1/responses");
        servletRequest.setAttribute(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY, principal);

        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler.class),
                attributes
        );

        assertTrue(allowed);
        assertEquals(principal, attributes.get(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY));
        verify(repository, never()).findByBearerKeyForModeration(eq("sk-test"));
    }

    @Test
    void resolvesBearerApiKeyIntoHandshakePrincipal() {
        GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
        GatewayResponsesHandshakeInterceptor interceptor = new GatewayResponsesHandshakeInterceptor(
                new GatewayRequestAuthSupport(),
                repository
        );
        when(repository.findByBearerKeyForModeration("sk-test")).thenReturn(Optional.of(new ModerationApiKeyContext(
                21L,
                "demo",
                "active",
                22L,
                "demo@api-private-router.local",
                "active",
                7,
                "user",
                23L,
                "openai-users",
                "openai",
                "standard",
                false,
                true,
                false
        )));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRequestURI("/v1/responses");
        servletRequest.addHeader("Authorization", "Bearer sk-test");

        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler.class),
                attributes
        );

        assertTrue(allowed);
        GatewayApiKeyPrincipal principal = (GatewayApiKeyPrincipal) attributes.get(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY);
        assertNotNull(principal);
        assertEquals(21L, principal.apiKeyId());
        assertEquals(22L, principal.userId());
        assertEquals("openai", principal.groupPlatform());
        verify(repository).touchLastUsed(21L);
    }

    @Test
    void leavesHandshakeUnauthenticatedWhenApiKeyMissingOrInactive() {
        GatewayApiKeyRepository repository = mock(GatewayApiKeyRepository.class);
        GatewayResponsesHandshakeInterceptor interceptor = new GatewayResponsesHandshakeInterceptor(
                new GatewayRequestAuthSupport(),
                repository
        );
        when(repository.findByBearerKeyForModeration("sk-dead")).thenReturn(Optional.of(new ModerationApiKeyContext(
                31L,
                "dead",
                "disabled",
                32L,
                "dead@api-private-router.local",
                "active",
                1,
                "user",
                33L,
                "legacy",
                "openai",
                "standard",
                false,
                true,
                false
        )));

        MockHttpServletRequest inactiveRequest = new MockHttpServletRequest();
        inactiveRequest.setRequestURI("/v1/responses");
        inactiveRequest.addHeader("Authorization", "Bearer sk-dead");
        Map<String, Object> inactiveAttributes = new HashMap<>();
        MockHttpServletResponse inactiveServletResponse = new MockHttpServletResponse();
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(inactiveRequest),
                new ServletServerHttpResponse(inactiveServletResponse),
                mock(WebSocketHandler.class),
                inactiveAttributes
        ));
        assertFalse(inactiveAttributes.containsKey(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY));
        assertEquals(401, inactiveServletResponse.getStatus());

        MockHttpServletRequest missingRequest = new MockHttpServletRequest();
        missingRequest.setRequestURI("/v1/responses");
        Map<String, Object> missingAttributes = new HashMap<>();
        MockHttpServletResponse missingServletResponse = new MockHttpServletResponse();
        assertFalse(interceptor.beforeHandshake(
                new ServletServerHttpRequest(missingRequest),
                new ServletServerHttpResponse(missingServletResponse),
                mock(WebSocketHandler.class),
                missingAttributes
        ));
        assertFalse(missingAttributes.containsKey(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY));
        assertEquals(401, missingServletResponse.getStatus());
        verify(repository, never()).touchLastUsed(anyLong());
    }
}
