package org.apiprivaterouter.javabackend.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.common.api.UnauthorizedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class GatewayApiKeyContextHolder {

    public static final String ATTR_GATEWAY_API_KEY = "api-private-router.gatewayApiKey";

    public GatewayApiKeyPrincipal requireApiKey() {
        GatewayApiKeyPrincipal principal = resolve();
        if (principal == null) {
            throw new UnauthorizedException("Invalid API key");
        }
        return principal;
    }

    public GatewayApiKeyPrincipal resolve() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object candidate = request.getAttribute(ATTR_GATEWAY_API_KEY);
        if (candidate instanceof GatewayApiKeyPrincipal principal) {
            return principal;
        }
        return null;
    }
}
