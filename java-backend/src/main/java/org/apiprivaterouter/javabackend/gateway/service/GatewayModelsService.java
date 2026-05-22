package org.apiprivaterouter.javabackend.gateway.service;

import org.apiprivaterouter.javabackend.availablechannels.model.UserSupportedModelResponse;
import org.apiprivaterouter.javabackend.availablechannels.service.AvailableChannelsService;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.gateway.model.ClaudeGatewayModelResponse;
import org.apiprivaterouter.javabackend.gateway.model.ClaudeGatewayModelsResponse;
import org.apiprivaterouter.javabackend.gateway.model.GeminiGatewayModelResponse;
import org.apiprivaterouter.javabackend.gateway.model.GeminiGatewayModelsResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GatewayModelsService {

    private static final String CLAUDE_CREATED_AT = "2024-01-01T00:00:00Z";
    private static final String DEFAULT_CLAUDE_CREATED_AT = "2025-01-01T00:00:00Z";
    private static final String DEFAULT_OPENAI_CREATED_AT = "2025-01-01T00:00:00Z";
    private static final List<String> DEFAULT_GEMINI_METHODS = List.of("generateContent", "streamGenerateContent");

    private final AvailableChannelsService availableChannelsService;

    public GatewayModelsService(AvailableChannelsService availableChannelsService) {
        this.availableChannelsService = availableChannelsService;
    }

    public ClaudeGatewayModelsResponse buildClaudeModels(CurrentUser currentUser, String platformHint) {
        List<String> ids = collectModelIds(currentUser, platformHint);
        if (ids.isEmpty()) {
            ids = switch (normalizePlatform(platformHint)) {
                case "openai" -> List.of("gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex", "gpt-image-1", "gpt-image-2");
                default -> List.of(
                        "claude-opus-4-5-20251101",
                        "claude-opus-4-6",
                        "claude-opus-4-7",
                        "claude-sonnet-4-5-20250929",
                        "claude-sonnet-4-6",
                        "claude-haiku-4-5-20251001"
                );
            };
        }
        String normalizedHint = normalizePlatform(platformHint);
        List<ClaudeGatewayModelResponse> models = ids.stream()
                .map(id -> new ClaudeGatewayModelResponse(
                        id,
                        "model",
                        id,
                        "openai".equals(normalizedHint) ? DEFAULT_OPENAI_CREATED_AT : DEFAULT_CLAUDE_CREATED_AT
                ))
                .toList();
        return new ClaudeGatewayModelsResponse("list", models);
    }

    public ClaudeGatewayModelsResponse buildAntigravityModels() {
        List<ClaudeGatewayModelResponse> models = List.of(
                new ClaudeGatewayModelResponse("claude-opus-4-5-20251101", "model", "Claude Opus 4.5", "2025-11-01T00:00:00Z"),
                new ClaudeGatewayModelResponse("claude-opus-4-6", "model", "Claude Opus 4.6", "2026-02-06T00:00:00Z"),
                new ClaudeGatewayModelResponse("claude-opus-4-7", "model", "Claude Opus 4.7", "2026-04-17T00:00:00Z"),
                new ClaudeGatewayModelResponse("claude-sonnet-4-5-20250929", "model", "Claude Sonnet 4.5", "2025-09-29T00:00:00Z"),
                new ClaudeGatewayModelResponse("claude-sonnet-4-6", "model", "Claude Sonnet 4.6", "2026-02-06T00:00:00Z"),
                new ClaudeGatewayModelResponse("claude-sonnet-4-7", "model", "Claude Sonnet 4.7", "2026-04-17T00:00:00Z"),
                new ClaudeGatewayModelResponse("claude-haiku-4-5-20251001", "model", "Claude Haiku 4.5", "2025-10-01T00:00:00Z"),
                new ClaudeGatewayModelResponse("gemini-2.0-flash", "model", "Gemini 2.0 Flash", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-2.5-flash", "model", "Gemini 2.5 Flash", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-2.5-flash-image", "model", "Gemini 2.5 Flash Image", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-2.5-pro", "model", "Gemini 2.5 Pro", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-3-flash-preview", "model", "Gemini 3 Flash Preview", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-3-pro-preview", "model", "Gemini 3 Pro Preview", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-3.1-pro-preview", "model", "Gemini 3.1 Pro Preview", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-3.1-pro-preview-customtools", "model", "Gemini 3.1 Pro Preview Custom Tools", CLAUDE_CREATED_AT),
                new ClaudeGatewayModelResponse("gemini-3.1-flash-image", "model", "Gemini 3.1 Flash Image", CLAUDE_CREATED_AT)
        );
        return new ClaudeGatewayModelsResponse("list", models);
    }

    public GeminiGatewayModelsResponse buildGeminiFallbackModels(boolean antigravity) {
        List<GeminiGatewayModelResponse> models = antigravity
                ? List.of(
                new GeminiGatewayModelResponse("models/gemini-2.0-flash", "Gemini 2.0 Flash", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-2.5-flash", "Gemini 2.5 Flash", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-2.5-flash-image", "Gemini 2.5 Flash Image", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-2.5-pro", "Gemini 2.5 Pro", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3-flash-preview", "Gemini 3 Flash Preview", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3-pro-preview", "Gemini 3 Pro Preview", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3.1-pro-preview-customtools", "Gemini 3.1 Pro Preview Custom Tools", DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3.1-flash-image", "Gemini 3.1 Flash Image", DEFAULT_GEMINI_METHODS)
        )
                : List.of(
                new GeminiGatewayModelResponse("models/gemini-2.0-flash", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-2.5-flash", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-2.5-flash-image", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-2.5-pro", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3-flash-preview", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3-pro-preview", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3.1-pro-preview", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3.1-pro-preview-customtools", null, DEFAULT_GEMINI_METHODS),
                new GeminiGatewayModelResponse("models/gemini-3.1-flash-image", null, DEFAULT_GEMINI_METHODS)
        );
        return new GeminiGatewayModelsResponse(models);
    }

    public GeminiGatewayModelResponse buildGeminiFallbackModel(String modelName, boolean antigravity) {
        String normalized = normalizeGeminiModelName(modelName);
        if (antigravity) {
            String displayName = normalized.substring("models/".length()).replace('-', ' ');
            return new GeminiGatewayModelResponse(normalized, titleCase(displayName), DEFAULT_GEMINI_METHODS);
        }
        return new GeminiGatewayModelResponse(normalized, null, DEFAULT_GEMINI_METHODS);
    }

    private List<String> collectModelIds(CurrentUser currentUser, String platformHint) {
        String normalizedHint = normalizePlatform(platformHint);
        Set<String> ids = new LinkedHashSet<>();
        availableChannelsService.getAvailableChannels(currentUser).forEach(channel ->
                channel.platforms().forEach(section -> {
                    String platform = normalizePlatform(section.platform());
                    if (!normalizedHint.isEmpty() && !normalizedHint.equals(platform)) {
                        return;
                    }
                    for (UserSupportedModelResponse model : section.supported_models()) {
                        if (model == null || model.name() == null || model.name().isBlank()) {
                            continue;
                        }
                        ids.add(model.name().trim());
                    }
                })
        );
        return List.copyOf(ids);
    }

    private String normalizePlatform(String platformHint) {
        if (platformHint == null || platformHint.isBlank()) {
            return "";
        }
        return platformHint.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeGeminiModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "models/unknown";
        }
        String normalized = modelName.trim();
        if (normalized.startsWith("models/")) {
            return normalized;
        }
        return "models/" + normalized;
    }

    private String titleCase(String value) {
        String[] parts = value.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
