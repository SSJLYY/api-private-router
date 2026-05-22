package org.apiprivaterouter.javabackend.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class GatewayRequestAuthSupport {

    public String extractApiKey(HttpServletRequest request) {
        boolean googleStyle = allowGoogleQueryKey(request);
        if (googleStyle) {
            String goog = trimToNull(request.getHeader("x-goog-api-key"));
            if (goog != null) {
                return goog;
            }
        }
        String authorization = trimToNull(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (authorization != null) {
            String[] parts = authorization.split(" ", 2);
            if (parts.length == 2 && "bearer".equalsIgnoreCase(parts[0])) {
                String bearer = trimToNull(parts[1]);
                if (bearer != null) {
                    return bearer;
                }
            }
        }
        String apiKey = trimToNull(request.getHeader("x-api-key"));
        if (apiKey != null) {
            return apiKey;
        }
        String goog = trimToNull(request.getHeader("x-goog-api-key"));
        if (goog != null) {
            return goog;
        }
        if (googleStyle) {
            return trimToNull(request.getParameter("key"));
        }
        return null;
    }

    public boolean allowGoogleQueryKey(HttpServletRequest request) {
        String path = normalizeEndpointPath(request);
        return path.startsWith("/v1beta") || path.startsWith("/antigravity/v1beta");
    }

    public String normalizeEndpointPath(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.trim();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
