package org.apiprivaterouter.javabackend.gateway.websocket;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.security.GatewayRequestAuthSupport;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ModerationApiKeyContext;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.repository.GatewayApiKeyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class GatewayResponsesHandshakeInterceptor implements HandshakeInterceptor {

    private final GatewayRequestAuthSupport authSupport;
    private final GatewayApiKeyRepository apiKeyRepository;

    public GatewayResponsesHandshakeInterceptor(
            GatewayRequestAuthSupport authSupport,
            GatewayApiKeyRepository apiKeyRepository
    ) {
        this.authSupport = authSupport;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return true;
        }
        HttpServletRequest rawRequest = servletRequest.getServletRequest();
        Object existing = rawRequest.getAttribute(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY);
        if (existing instanceof GatewayApiKeyPrincipal principal) {
            attributes.put(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY, principal);
            return true;
        }
        String inboundApiKey = authSupport.extractApiKey(rawRequest);
        if (inboundApiKey == null || inboundApiKey.isBlank()) {
            return rejectUnauthorized(response);
        }
        ModerationApiKeyContext apiKeyContext = apiKeyRepository.findByBearerKeyForModeration(inboundApiKey).orElse(null);
        if (apiKeyContext == null || !isActive(apiKeyContext.apiKeyStatus()) || !isActive(apiKeyContext.userStatus())) {
            return rejectUnauthorized(response);
        }
        attributes.put(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY, new GatewayApiKeyPrincipal(
                apiKeyContext.apiKeyId(),
                apiKeyContext.apiKeyName(),
                apiKeyContext.apiKeyStatus(),
                apiKeyContext.userId(),
                apiKeyContext.userEmail(),
                apiKeyContext.userStatus(),
                apiKeyContext.userConcurrency(),
                apiKeyContext.userRole(),
                apiKeyContext.groupId(),
                apiKeyContext.groupName(),
                apiKeyContext.groupPlatform(),
                apiKeyContext.groupSubscriptionType(),
                apiKeyContext.groupAllowImageGeneration(),
                apiKeyContext.groupAllowMessagesDispatch(),
                apiKeyContext.groupClaudeCodeOnly()
        ));
        apiKeyRepository.touchLastUsed(apiKeyContext.apiKeyId());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }

    private boolean isActive(String status) {
        return "active".equalsIgnoreCase(status);
    }

    private boolean rejectUnauthorized(ServerHttpResponse response) {
        if (response != null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
        }
        return false;
    }
}
