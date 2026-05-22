package org.apiprivaterouter.javabackend.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.springframework.stereotype.Component;

@Component
public class GatewayAccessPolicy {

    public void requireGroupedAccess(GatewayApiKeyPrincipal principal, HttpServletRequest request) {
        if (principal.groupId() != null) {
            return;
        }
        String path = request == null ? "" : request.getRequestURI();
        if (path != null && (path.startsWith("/v1beta") || path.startsWith("/antigravity/v1beta"))) {
            throw googleError(403, "API Key is not assigned to any group and cannot be used. Please contact the administrator to assign it to a group.");
        }
        throw anthropicError(403, "API Key is not assigned to any group and cannot be used. Please contact the administrator to assign it to a group.");
    }

    public void requirePlatform(GatewayApiKeyPrincipal principal, String expectedPlatform) {
        if (expectedPlatform == null || expectedPlatform.isBlank()) {
            return;
        }
        String actual = principal.groupPlatform() == null ? "" : principal.groupPlatform().trim().toLowerCase();
        String expected = expectedPlatform.trim().toLowerCase();
        if (!expected.equals(actual)) {
            if ("gemini".equals(expected)) {
                throw googleError(400, "API key group platform is not gemini");
            }
            throw anthropicError(403, "API key group platform is not " + expected);
        }
    }

    public ApiErrorException anthropicError(int status, String message) {
        return new ApiErrorException(status, "permission_error", message);
    }

    public ApiErrorException googleError(int status, String message) {
        return new ApiErrorException(status, googleStatus(status), message);
    }

    private String googleStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 499 -> "CANCELLED";
            case 500 -> "INTERNAL";
            case 501 -> "UNIMPLEMENTED";
            case 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> "UNKNOWN";
        };
    }
}
