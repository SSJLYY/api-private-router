package org.apiprivaterouter.javabackend.gateway.service.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.service.GatewayUsageLoggingService;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Service
public class OpenAiEmbeddingsService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingsService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gateway.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${gateway.openai.api-key:}")
    private String apiKey;

    @Value("${gateway.openai.proxy-host:}")
    private String proxyHost;

    @Value("${gateway.openai.proxy-port:0}")
    private int proxyPort;

    private final RestTemplate restTemplate;
    private final GatewayUsageLoggingService usageLoggingService;
    private final GatewayApiKeyContextHolder apiKeyContextHolder;

    public OpenAiEmbeddingsService(RestTemplate restTemplate, GatewayUsageLoggingService usageLoggingService, GatewayApiKeyContextHolder apiKeyContextHolder) {
        this.restTemplate = restTemplate;
        this.usageLoggingService = usageLoggingService;
        this.apiKeyContextHolder = apiKeyContextHolder;
    }

    public void forwardEmbeddings(HttpServletRequest request, HttpServletResponse response,
                                   String requestBody, String model) throws IOException {
        long startTime = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        try {
            ObjectNode body = (ObjectNode) objectMapper.readTree(requestBody);

            String upstreamModel = resolveUpstreamModel(model);
            body.put("model", upstreamModel);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("User-Agent", "api-private-router/1.0");

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> upstreamResponse = restTemplate.exchange(
                    baseUrl + "/v1/embeddings",
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (upstreamResponse.getStatusCode().is2xxSuccessful()) {
                JsonNode responseBody = objectMapper.readTree(upstreamResponse.getBody());
                JsonNode usage = responseBody.get("usage");

                long inputTokens = 0;
                long cacheReadTokens = 0;
                long cacheCreationTokens = 0;
                if (usage != null) {
                    inputTokens = usage.path("prompt_tokens").asLong(0);
                    if (inputTokens == 0) inputTokens = usage.path("input_tokens").asLong(0);
                    cacheReadTokens = extractCacheTokens(usage, "cached_tokens", "cache_read_tokens", "cache_read_input_tokens");
                    cacheCreationTokens = extractCacheTokens(usage, "cache_creation_tokens", "cache_creation_input_tokens");
                }

                long duration = System.currentTimeMillis() - startTime;
                var principal = apiKeyContextHolder.resolve();
                usageLoggingService.logUsage(
                        principal != null ? principal.apiKeyId() : 0,
                        principal != null ? principal.userId() : 0,
                        model, upstreamModel,
                        inputTokens, 0, cacheReadTokens, cacheCreationTokens,
                        false, duration, "openai", "embeddings"
                );

                response.setStatus(upstreamResponse.getStatusCode().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(upstreamResponse.getBody());
            } else {
                handleError(response, upstreamResponse.getStatusCode().value(), upstreamResponse.getBody(), model, requestId);
            }
        } catch (Exception ex) {
            log.error("embeddings forward error: {}", ex.getMessage());
            long duration = System.currentTimeMillis() - startTime;
            var principal = apiKeyContextHolder.resolve();
            usageLoggingService.logUsage(
                    0L, principal != null ? principal.userId() : 0,
                    model, model, 0, 0, 0, 0,
                    false, duration, "openai", "embeddings"
            );
            sendErrorResponse(response, 502, "upstream_error", "Failed to forward embeddings request", model, requestId);
        }
    }

    private long extractCacheTokens(JsonNode usage, String... paths) {
        for (String path : paths) {
            if (usage.has(path)) return usage.get(path).asLong(0);
            if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").has(path)) {
                return usage.get("prompt_tokens_details").get(path).asLong(0);
            }
            if (usage.has("input_tokens_details") && usage.get("input_tokens_details").has(path)) {
                return usage.get("input_tokens_details").get(path).asLong(0);
            }
        }
        return 0;
    }

    private String resolveUpstreamModel(String model) {
        if (model == null || model.isBlank()) return "text-embedding-3-small";
        return switch (model.toLowerCase()) {
            case "text-embedding-3-large" -> "text-embedding-3-large";
            case "text-embedding-ada-002" -> "text-embedding-ada-002";
            default -> model;
        };
    }

    private void handleError(HttpServletResponse response, int statusCode, String body, String model, String requestId) throws IOException {
        if (statusCode >= 400 && statusCode < 500) {
            response.setStatus(statusCode);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(body);
        } else {
            sendErrorResponse(response, 502, "upstream_error", "Upstream embeddings error", model, requestId);
        }
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String code, String message, String model, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ObjectNode error = objectMapper.createObjectNode();
        error.putObject("error")
                .put("code", code)
                .put("message", message)
                .put("type", "upstream_error");
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
