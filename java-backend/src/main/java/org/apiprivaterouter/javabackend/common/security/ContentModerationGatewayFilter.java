package org.apiprivaterouter.javabackend.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.ContentModerationRuntimeService;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.extractor.ContentModerationInputExtractor;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationCheckInput;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationDecision;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ModerationApiKeyContext;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.repository.GatewayApiKeyRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Component
public class ContentModerationGatewayFilter extends OncePerRequestFilter {

    private final GatewayApiKeyRepository apiKeyRepository;
    private final ContentModerationRuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    public ContentModerationGatewayFilter(
            GatewayApiKeyRepository apiKeyRepository,
            ContentModerationRuntimeService runtimeService,
            ObjectMapper objectMapper
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!shouldCheck(request) || !runtimeService.isRiskControlEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);
        String inboundApiKey = extractApiKey(request);
        if (inboundApiKey == null || inboundApiKey.isBlank()) {
            filterChain.doFilter(wrapped, response);
            return;
        }

        ModerationApiKeyContext apiKeyContext = apiKeyRepository.findByBearerKeyForModeration(inboundApiKey).orElse(null);
        if (apiKeyContext == null || !isActive(apiKeyContext.apiKeyStatus()) || !isActive(apiKeyContext.userStatus())) {
            filterChain.doFilter(wrapped, response);
            return;
        }

        String protocol = resolveProtocol(request);
        String model = resolveModel(request, body);
        ContentModerationDecision decision = runtimeService.check(new ContentModerationCheckInput(
                firstNonBlank(request.getHeader("X-Request-Id"), UUID.randomUUID().toString()),
                apiKeyContext.userId(),
                apiKeyContext.userEmail(),
                apiKeyContext.apiKeyId(),
                apiKeyContext.apiKeyName(),
                apiKeyContext.groupId(),
                apiKeyContext.groupName(),
                normalizeEndpointPath(request),
                firstNonBlank(apiKeyContext.groupPlatform(), inferProvider(protocol, request)),
                model,
                protocol,
                body
        ));
        if (decision != null && decision.blocked()) {
            writeBlockedResponse(response, protocol, decision.statusCode(), decision.message());
            return;
        }

        apiKeyRepository.touchLastUsed(apiKeyContext.apiKeyId());
        filterChain.doFilter(wrapped, response);
    }

    private boolean shouldCheck(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.isBlank() || !"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return path.startsWith("/v1/messages")
                || path.startsWith("/v1/chat/completions")
                || path.startsWith("/openai/v1/chat/completions")
                || path.startsWith("/v1/responses")
                || path.startsWith("/responses")
                || path.startsWith("/openai/v1/responses")
                || path.startsWith("/backend-api/codex/responses")
                || path.startsWith("/v1/images/")
                || path.startsWith("/openai/v1/images/")
                || path.startsWith("/v1beta/models/")
                || path.startsWith("/antigravity/v1/messages")
                || path.startsWith("/antigravity/v1beta/models/");
    }

    private String extractApiKey(HttpServletRequest request) {
        boolean googleStyle = allowGoogleQueryKey(request);
        if (googleStyle) {
            String goog = trimToNull(request.getHeader("x-goog-api-key"));
            if (goog != null) {
                return goog;
            }
        }
        String auth = trimToNull(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (auth != null) {
            String[] parts = auth.split(" ", 2);
            if (parts.length == 2 && "bearer".equalsIgnoreCase(parts[0])) {
                return trimToNull(parts[1]);
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

    private String resolveProtocol(HttpServletRequest request) {
        String path = normalizeEndpointPath(request);
        if (path.startsWith("/v1/chat/completions") || path.startsWith("/openai/v1/chat/completions")) {
            return ContentModerationInputExtractor.PROTOCOL_OPENAI_CHAT;
        }
        if (path.startsWith("/v1/responses")
                || path.startsWith("/responses")
                || path.startsWith("/openai/v1/responses")
                || path.startsWith("/backend-api/codex/responses")) {
            return ContentModerationInputExtractor.PROTOCOL_OPENAI_RESPONSES;
        }
        if (path.startsWith("/v1beta/models") || path.startsWith("/antigravity/v1beta/models")) {
            return ContentModerationInputExtractor.PROTOCOL_GEMINI;
        }
        if (path.startsWith("/v1/images/")) {
            return ContentModerationInputExtractor.PROTOCOL_OPENAI_IMAGES;
        }
        return ContentModerationInputExtractor.PROTOCOL_ANTHROPIC_MESSAGES;
    }

    private String inferProvider(String protocol, HttpServletRequest request) {
        if (ContentModerationInputExtractor.PROTOCOL_GEMINI.equals(protocol)) {
            return "gemini";
        }
        String path = normalizeEndpointPath(request);
        if (path.startsWith("/v1/chat/completions")
                || path.startsWith("/openai/v1/chat/completions")
                || path.startsWith("/responses")
                || path.startsWith("/v1/responses")
                || path.startsWith("/openai/v1/responses")
                || path.startsWith("/backend-api/codex/responses")
                || path.startsWith("/v1/images/")
                || path.startsWith("/openai/v1/images/")) {
            return "openai";
        }
        return "anthropic";
    }

    private String resolveModel(HttpServletRequest request, byte[] body) {
        String pathModel = extractGeminiModelFromPath(request);
        if (pathModel != null) {
            return pathModel;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String model = trimToNull(root.path("model").asText(null));
            if (model != null) {
                return model;
            }
            String nameModel = trimToNull(root.path("name").asText(null));
            return nameModel == null ? "" : nameModel;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void writeBlockedResponse(HttpServletResponse response, String protocol, int statusCode, String message) throws IOException {
        int status = statusCode <= 0 ? 403 : statusCode;
        String payload;
        if (ContentModerationInputExtractor.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            payload = "{\"error\":{\"code\":\"content_policy_violation\",\"message\":\"" + escapeJson(message) + "\"}}";
        } else if (ContentModerationInputExtractor.PROTOCOL_OPENAI_CHAT.equals(protocol)) {
            payload = "{\"error\":{\"type\":\"content_policy_violation\",\"message\":\"" + escapeJson(message) + "\"}}";
        } else if (ContentModerationInputExtractor.PROTOCOL_GEMINI.equals(protocol)) {
            payload = "{\"error\":{\"code\":" + status + ",\"message\":\"" + escapeJson(message) + "\",\"status\":\""
                    + googleStatus(status) + "\"}}";
        } else {
            payload = "{\"type\":\"error\",\"error\":{\"type\":\"content_policy_violation\",\"message\":\"" + escapeJson(message) + "\"}}";
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(payload);
    }

    private boolean isActive(String status) {
        return "active".equalsIgnoreCase(trimToNull(status));
    }

    private String normalizeEndpointPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
    }

    private boolean allowGoogleQueryKey(HttpServletRequest request) {
        String path = normalizeEndpointPath(request);
        return path.startsWith("/v1beta") || path.startsWith("/antigravity/v1beta");
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
            case 501 -> "UNIMPLEMENTED";
            case 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> statusCode >= 500 ? "INTERNAL" : "PERMISSION_DENIED";
        };
    }

    private String extractGeminiModelFromPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        int marker = path.indexOf("/v1beta/models/");
        if (marker < 0) {
            return null;
        }
        String suffix = path.substring(marker + "/v1beta/models/".length());
        if (suffix.isBlank()) {
            return null;
        }
        int colon = suffix.indexOf(':');
        if (colon >= 0) {
            suffix = suffix.substring(0, colon);
        }
        int slash = suffix.indexOf('/');
        if (slash >= 0) {
            suffix = suffix.substring(0, slash);
        }
        return trimToNull(suffix);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String first, String fallback) {
        String normalized = trimToNull(first);
        return normalized == null ? (fallback == null ? "" : fallback) : normalized;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
