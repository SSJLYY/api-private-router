package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationApiKeyStatus;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationTestAuditResult;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.TestContentModerationApiKeysRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.TestContentModerationApiKeysResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContentModerationAuditService {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationAuditService.class);
    private final ObjectMapper objectMapper;
    private final ContentModerationApiKeyHealthTracker apiKeyHealthTracker;
    private final ContentModerationRuntimeService runtimeService;
    private final HttpClient httpClient;

    public ContentModerationAuditService(
            ObjectMapper objectMapper,
            ContentModerationApiKeyHealthTracker apiKeyHealthTracker,
            ContentModerationRuntimeService runtimeService
    ) {
        this.objectMapper = objectMapper;
        this.apiKeyHealthTracker = apiKeyHealthTracker;
        this.runtimeService = runtimeService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public TestContentModerationApiKeysResponse testApiKeys(
            ContentModerationLoadedConfig loadedConfig,
            TestContentModerationApiKeysRequest request
    ) {
        List<String> requestKeys = ContentModerationSupport.normalizeApiKeys(request == null ? List.of() : request.api_keys());
        boolean configured = requestKeys.isEmpty();
        List<String> apiKeys = configured ? loadedConfig.apiKeys() : requestKeys;
        String baseUrl = resolveBaseUrl(request == null ? null : request.base_url(), configured ? loadedConfig.response().base_url() : ContentModerationSupport.DEFAULT_BASE_URL);
        String model = resolveModel(request == null ? null : request.model(), configured ? loadedConfig.response().model() : ContentModerationSupport.DEFAULT_MODEL);
        int timeoutMs = resolveTimeout(request == null ? null : request.timeout_ms(), configured ? loadedConfig.response().timeout_ms() : ContentModerationSupport.DEFAULT_TIMEOUT_MS);
        List<String> images = ContentModerationSupport.normalizeImages(request == null ? List.of() : request.images());
        Object moderationInput = buildModerationInput(request == null ? null : request.prompt(), images);

        List<ContentModerationApiKeyStatus> items = new ArrayList<>(apiKeys.size());
        ContentModerationTestAuditResult auditResult = null;
        for (int index = 0; index < apiKeys.size(); index++) {
            TestModerationOutcome outcome = testSingleApiKey(
                    index,
                    apiKeys.get(index),
                    configured,
                    baseUrl,
                    model,
                    timeoutMs,
                    moderationInput,
                    loadedConfig.thresholds()
            );
            items.add(outcome.status());
            if (auditResult == null) {
                auditResult = outcome.auditResult();
            }
        }
        return new TestContentModerationApiKeysResponse(items, auditResult, images.size());
    }

    private TestModerationOutcome testSingleApiKey(
            int index,
            String apiKey,
            boolean configured,
            String baseUrl,
            String model,
            int timeoutMs,
            Object moderationInput,
            Map<String, Double> thresholds
    ) {
        long startedAt = System.nanoTime();
        int httpStatus = 0;
        runtimeService.recordProcessingStarted();
        try {
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "input", moderationInput
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/moderations"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            httpStatus = response.statusCode();
            if (httpStatus < 200 || httpStatus >= 300) {
                String error = extractUpstreamError(response.body(), httpStatus);
                apiKeyHealthTracker.markFailure(apiKey, error, ContentModerationSupport.elapsedMillis(startedAt), httpStatus);
                runtimeService.recordProcessingCompleted(true);
                return new TestModerationOutcome(apiKeyHealthTracker.buildStatus(index, apiKey, configured), null);
            }
            ContentModerationTestAuditResult auditResult = parseAuditResult(response.body(), thresholds);
            apiKeyHealthTracker.markSuccess(apiKey, ContentModerationSupport.elapsedMillis(startedAt), httpStatus);
            runtimeService.recordProcessingCompleted(false);
            return new TestModerationOutcome(apiKeyHealthTracker.buildStatus(index, apiKey, configured), auditResult);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            apiKeyHealthTracker.markFailure(apiKey, "request interrupted", ContentModerationSupport.elapsedMillis(startedAt), httpStatus);
            runtimeService.recordProcessingCompleted(true);
            return new TestModerationOutcome(apiKeyHealthTracker.buildStatus(index, apiKey, configured), null);
        } catch (Exception ex) {
            apiKeyHealthTracker.markFailure(apiKey, ContentModerationSupport.trimErrorMessage(ex), ContentModerationSupport.elapsedMillis(startedAt), httpStatus);
            runtimeService.recordProcessingCompleted(true);
            return new TestModerationOutcome(apiKeyHealthTracker.buildStatus(index, apiKey, configured), null);
        }
    }

    private Object buildModerationInput(String prompt, List<String> images) {
        if (images.size() > ContentModerationSupport.MAX_TEST_IMAGES) {
            throw new IllegalArgumentException("images supports at most " + ContentModerationSupport.MAX_TEST_IMAGES + " items");
        }
        String normalizedPrompt = ContentModerationSupport.trimToNull(prompt);
        if (images.isEmpty()) {
            return normalizedPrompt == null ? "hello" : normalizedPrompt;
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (normalizedPrompt != null) {
            parts.add(Map.of(
                    "type", "text",
                    "text", normalizedPrompt
            ));
        }
        for (String image : images) {
            if (!image.startsWith("data:image/") || !image.contains(";base64,")) {
                throw new IllegalArgumentException("images must be data URLs");
            }
            parts.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", image)
            ));
        }
        return parts;
    }

    private ContentModerationTestAuditResult parseAuditResult(String body, Map<String, Double> thresholds) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
        JsonNode first = results.isArray() && !results.isEmpty() ? results.get(0) : objectMapper.createObjectNode();
        boolean flagged = first.path("flagged").asBoolean(false);
        JsonNode categoryScoresNode = first.path("category_scores");
        Map<String, Double> categoryScores = new LinkedHashMap<>();
        String highestCategory = "";
        double highestScore = 0D;
        if (categoryScoresNode.isObject()) {
            var fields = categoryScoresNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                double score = field.getValue().asDouble(0D);
                categoryScores.put(field.getKey(), score);
                if (highestCategory.isEmpty() || score > highestScore) {
                    highestCategory = field.getKey();
                    highestScore = score;
                }
                if (score >= thresholds.getOrDefault(field.getKey(), 1D)) {
                    flagged = true;
                }
            }
        }
        return new ContentModerationTestAuditResult(
                flagged,
                highestCategory,
                highestScore,
                highestScore,
                categoryScores,
                thresholds
        );
    }

    private String extractUpstreamError(String body, int httpStatus) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = ContentModerationSupport.trimToNull(root.path("error").path("message").asText(null));
            if (message == null) {
                message = ContentModerationSupport.trimToNull(root.path("message").asText(null));
            }
            if (message != null) {
                return message;
            }
        } catch (Exception ex) {
            log.debug("Failed to parse upstream error JSON for audit: {}", ex.getMessage());
        }
        String compactBody = ContentModerationSupport.trimToNull(body == null ? null : body.replaceAll("\\s+", " "));
        if (compactBody != null) {
            return ContentModerationSupport.truncate(compactBody, 180);
        }
        return "HTTP " + httpStatus;
    }

    private String resolveBaseUrl(String requestValue, String fallback) {
        String normalized = ContentModerationSupport.trimToNull(requestValue);
        return ContentModerationSupport.normalizeBaseUrl(normalized == null ? fallback : normalized);
    }

    private String resolveModel(String requestValue, String fallback) {
        String normalized = ContentModerationSupport.trimToNull(requestValue);
        return ContentModerationSupport.normalizeModel(normalized == null ? fallback : normalized);
    }

    private int resolveTimeout(Integer requestValue, int fallback) {
        return ContentModerationSupport.normalizeTimeout(requestValue == null || requestValue <= 0 ? fallback : requestValue);
    }

    private record TestModerationOutcome(
            ContentModerationApiKeyStatus status,
            ContentModerationTestAuditResult auditResult
    ) {
    }
}
