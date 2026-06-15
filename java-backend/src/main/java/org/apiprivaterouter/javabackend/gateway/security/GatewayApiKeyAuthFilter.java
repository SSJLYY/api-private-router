package org.apiprivaterouter.javabackend.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ModerationApiKeyContext;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.repository.GatewayApiKeyRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Component
public class GatewayApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayApiKeyAuthFilter.class);

    private static final String[] PROTECTED_PREFIXES = {
            "/v1/",
            "/v1beta/",
            "/openai/",
            "/antigravity/",
            "/responses",
            "/backend-api/codex",
            "/chat/",
            "/images/"
    };

    private final GatewayRequestAuthSupport authSupport;
    private final GatewayApiKeyRepository apiKeyRepository;

    public GatewayApiKeyAuthFilter(GatewayRequestAuthSupport authSupport, GatewayApiKeyRepository apiKeyRepository) {
        this.authSupport = authSupport;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!shouldInspect(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String inboundApiKey = authSupport.extractApiKey(request);
        if (inboundApiKey == null || inboundApiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        ModerationApiKeyContext apiKeyContext = apiKeyRepository.findByBearerKeyForModeration(inboundApiKey).orElse(null);
        if (apiKeyContext == null || !isActive(apiKeyContext.apiKeyStatus()) || !isActive(apiKeyContext.userStatus())) {
            log.debug("Gateway auth rejected: apiKeyFound={}, keyStatus={}, userStatus={}",
                    apiKeyContext != null, apiKeyContext != null ? apiKeyContext.apiKeyStatus() : "n/a",
                    apiKeyContext != null ? apiKeyContext.userStatus() : "n/a");
            response.setStatus(401);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":{\"message\":\"Invalid API key\"}}");
            return;
        }
        request.setAttribute(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY, new GatewayApiKeyPrincipal(
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
        filterChain.doFilter(request, response);
    }

    private boolean shouldInspect(HttpServletRequest request) {
        String path = authSupport.normalizeEndpointPath(request);
        if (path.isBlank() || path.startsWith("/api/") || path.startsWith("/actuator") || path.startsWith("/error")) {
            return false;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActive(String status) {
        return "active".equalsIgnoreCase(status);
    }
}
