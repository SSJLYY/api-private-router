package org.apiprivaterouter.javabackend.gateway.service;

import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BedrockModelResolver {

    private static final String DEFAULT_BEDROCK_REGION = "us-east-1";
    private static final List<String> CROSS_REGION_PREFIXES = List.of("us.", "eu.", "apac.", "jp.", "au.", "us-gov.", "global.");
    private static final List<String> BEDROCK_MODEL_PREFIXES = List.of(
            "anthropic.",
            "amazon.",
            "meta.",
            "mistral.",
            "cohere.",
            "ai21.",
            "deepseek.",
            "stability.",
            "writer.",
            "nova."
    );

    private static final Map<String, String> DEFAULT_BEDROCK_MODEL_MAPPING = createDefaultMapping();

    public String resolveModelId(AdminAccountResponse account, String requestedModel) {
        if (account == null) {
            return null;
        }
        String mappedModel = resolveMappedModel(account, requestedModel);
        NormalizedBedrockModel normalized = normalizeBedrockModelId(mappedModel);
        if (!normalized.valid()) {
            return null;
        }
        if (!normalized.adjustRegion()) {
            return normalized.modelId();
        }
        String targetRegion = shouldForceGlobal(account) ? "global" : runtimeRegion(account);
        return adjustRegionPrefix(normalized.modelId(), targetRegion);
    }

    public String resolveMappedModel(AdminAccountResponse account, String requestedModel) {
        Map<String, String> mapping = extractStringMap(account == null || account.credentials() == null
                ? null
                : account.credentials().get("model_mapping"));
        return resolveFromMapping(mapping, requestedModel);
    }

    public String buildBedrockUrl(String region, String modelId, boolean stream) {
        String effectiveRegion = trimToEmpty(region);
        if (effectiveRegion.isEmpty()) {
            effectiveRegion = DEFAULT_BEDROCK_REGION;
        }
        String encodedModelId = java.net.URLEncoder.encode(trimToEmpty(modelId), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        if (stream) {
            return "https://bedrock-runtime.%s.amazonaws.com/model/%s/invoke-with-response-stream".formatted(effectiveRegion, encodedModelId);
        }
        return "https://bedrock-runtime.%s.amazonaws.com/model/%s/invoke".formatted(effectiveRegion, encodedModelId);
    }

    public String runtimeRegion(AdminAccountResponse account) {
        if (account == null || account.credentials() == null) {
            return DEFAULT_BEDROCK_REGION;
        }
        String region = stringValue(account.credentials().get("aws_region"));
        return region.isEmpty() ? DEFAULT_BEDROCK_REGION : region;
    }

    public boolean shouldForceGlobal(AdminAccountResponse account) {
        if (account == null || account.credentials() == null) {
            return false;
        }
        return "true".equalsIgnoreCase(stringValue(account.credentials().get("aws_force_global")));
    }

    public String adjustRegionPrefix(String modelId, String region) {
        String targetPrefix = "global".equals(trimToEmpty(region))
                ? "global"
                : crossRegionPrefix(region);
        for (String prefix : CROSS_REGION_PREFIXES) {
            if (modelId.startsWith(prefix)) {
                if (prefix.equals(targetPrefix + ".")) {
                    return modelId;
                }
                return targetPrefix + "." + modelId.substring(prefix.length());
            }
        }
        return modelId;
    }

    public String crossRegionPrefix(String region) {
        String normalized = trimToEmpty(region).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("us-gov")) {
            return "us-gov";
        }
        if (normalized.startsWith("us-")) {
            return "us";
        }
        if (normalized.startsWith("eu-")) {
            return "eu";
        }
        if ("ap-northeast-1".equals(normalized)) {
            return "jp";
        }
        if ("ap-southeast-2".equals(normalized)) {
            return "au";
        }
        if (normalized.startsWith("ap-")) {
            return "apac";
        }
        if (normalized.startsWith("ca-") || normalized.startsWith("sa-")) {
            return "us";
        }
        return "us";
    }

    public boolean isLikelyBedrockModelId(String modelId) {
        String normalized = trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.startsWith("arn:")) {
            return true;
        }
        if (isRegionalBedrockModelId(normalized)) {
            return true;
        }
        for (String prefix : BEDROCK_MODEL_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRegionalBedrockModelId(String modelId) {
        String normalized = trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        for (String prefix : CROSS_REGION_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private NormalizedBedrockModel normalizeBedrockModelId(String modelId) {
        String trimmed = trimToEmpty(modelId);
        if (trimmed.isEmpty()) {
            return new NormalizedBedrockModel("", false, false);
        }
        String defaultMapped = DEFAULT_BEDROCK_MODEL_MAPPING.get(trimmed);
        if (defaultMapped != null) {
            return new NormalizedBedrockModel(defaultMapped, true, true);
        }
        if (isRegionalBedrockModelId(trimmed)) {
            return new NormalizedBedrockModel(trimmed, true, true);
        }
        if (isLikelyBedrockModelId(trimmed)) {
            return new NormalizedBedrockModel(trimmed, false, true);
        }
        return new NormalizedBedrockModel("", false, false);
    }

    private String resolveFromMapping(Map<String, String> mapping, String requestedModel) {
        String lookup = trimToEmpty(requestedModel);
        if (mapping.isEmpty()) {
            return lookup;
        }
        String exact = trimToEmpty(mapping.get(lookup));
        if (!exact.isEmpty()) {
            return exact;
        }
        String bestMatch = null;
        int bestScore = -1;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String pattern = trimToEmpty(entry.getKey());
            String target = trimToEmpty(entry.getValue());
            if (pattern.isEmpty() || target.isEmpty() || !pattern.contains("*")) {
                continue;
            }
            String regex = java.util.regex.Pattern.quote(pattern).replace("\\*", ".*");
            if (!lookup.matches(regex)) {
                continue;
            }
            int score = pattern.replace("*", "").length();
            if (score > bestScore) {
                bestScore = score;
                bestMatch = target;
            }
        }
        return bestMatch == null ? lookup : bestMatch;
    }

    private Map<String, String> extractStringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = trimToEmpty(entry.getKey().toString());
            String val = trimToEmpty(entry.getValue().toString());
            if (!key.isEmpty() && !val.isEmpty()) {
                result.put(key, val);
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? "" : trimToEmpty(String.valueOf(value));
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> createDefaultMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("claude-opus-4-7", "us.anthropic.claude-opus-4-7-v1");
        mapping.put("claude-opus-4-6-thinking", "us.anthropic.claude-opus-4-6-v1");
        mapping.put("claude-opus-4-6", "us.anthropic.claude-opus-4-6-v1");
        mapping.put("claude-opus-4-5-thinking", "us.anthropic.claude-opus-4-5-20251101-v1:0");
        mapping.put("claude-opus-4-5-20251101", "us.anthropic.claude-opus-4-5-20251101-v1:0");
        mapping.put("claude-opus-4-1", "us.anthropic.claude-opus-4-1-20250805-v1:0");
        mapping.put("claude-opus-4-20250514", "us.anthropic.claude-opus-4-20250514-v1:0");
        mapping.put("claude-sonnet-4-6-thinking", "us.anthropic.claude-sonnet-4-6");
        mapping.put("claude-sonnet-4-6", "us.anthropic.claude-sonnet-4-6");
        mapping.put("claude-sonnet-4-5", "us.anthropic.claude-sonnet-4-5-20250929-v1:0");
        mapping.put("claude-sonnet-4-5-thinking", "us.anthropic.claude-sonnet-4-5-20250929-v1:0");
        mapping.put("claude-sonnet-4-5-20250929", "us.anthropic.claude-sonnet-4-5-20250929-v1:0");
        mapping.put("claude-sonnet-4-20250514", "us.anthropic.claude-sonnet-4-20250514-v1:0");
        mapping.put("claude-haiku-4-5", "us.anthropic.claude-haiku-4-5-20251001-v1:0");
        mapping.put("claude-haiku-4-5-20251001", "us.anthropic.claude-haiku-4-5-20251001-v1:0");
        return Map.copyOf(mapping);
    }

    private record NormalizedBedrockModel(String modelId, boolean adjustRegion, boolean valid) {
    }
}
