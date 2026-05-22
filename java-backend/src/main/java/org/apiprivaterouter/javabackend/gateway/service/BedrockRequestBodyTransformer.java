package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BedrockRequestBodyTransformer {

    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";
    private static final Pattern CLAUDE_VERSION_PATTERN = Pattern.compile("claude-(?:haiku|sonnet|opus)-(\\d+)[-.](\\d+)");
    private static final Set<String> SUPPORTED_BETA_TOKENS = Set.of(
            "computer-use-2025-01-24",
            "computer-use-2025-11-24",
            "context-1m-2025-08-07",
            "context-management-2025-06-27",
            "compact-2026-01-12",
            "interleaved-thinking-2025-05-14",
            "tool-search-tool-2025-10-19",
            "tool-examples-2025-10-29"
    );

    private final ObjectMapper objectMapper;

    public BedrockRequestBodyTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode transform(ObjectNode requestBody, String modelId, String betaHeader) {
        List<String> betaTokens = resolveBetaTokens(betaHeader, requestBody, modelId);
        return transformWithResolvedTokens(requestBody, modelId, betaTokens);
    }

    public ObjectNode transformWithResolvedTokens(ObjectNode requestBody, String modelId, List<String> betaTokens) {
        ObjectNode transformed = requestBody == null ? objectMapper.createObjectNode() : requestBody.deepCopy();
        transformed.put("anthropic_version", ANTHROPIC_VERSION);
        if (!betaTokens.isEmpty()) {
            ArrayNode anthropicBeta = objectMapper.createArrayNode();
            betaTokens.forEach(anthropicBeta::add);
            transformed.set("anthropic_beta", anthropicBeta);
        } else {
            transformed.remove("anthropic_beta");
        }
        transformed.remove("model");
        transformed.remove("stream");
        inlineOutputFormatSchema(transformed);
        transformed.remove("output_config");
        removeCustomFieldFromTools(transformed);
        sanitizeCacheControl(transformed, modelId);
        return transformed;
    }

    public List<String> resolveBetaTokens(String betaHeader, JsonNode requestBody, String modelId) {
        List<String> tokens = parseAnthropicBetaHeader(betaHeader);
        tokens = autoInjectBetaTokens(tokens, requestBody, modelId);
        return filterBetaTokens(tokens);
    }

    public List<String> parseAnthropicBetaHeader(String header) {
        String trimmed = trimToEmpty(header);
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                if (node instanceof ArrayNode array) {
                    List<String> tokens = new ArrayList<>();
                    for (JsonNode item : array) {
                        String token = trimToEmpty(item.asText());
                        if (!token.isEmpty()) {
                            tokens.add(token);
                        }
                    }
                    return tokens;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        List<String> tokens = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String token = trimToEmpty(part);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public List<String> autoInjectBetaTokens(List<String> tokens, JsonNode requestBody, String modelId) {
        List<String> mutable = new ArrayList<>(tokens);
        Set<String> seen = new LinkedHashSet<>(mutable);
        if (requestBody != null && requestBody.has("thinking")) {
            inject(seen, mutable, "interleaved-thinking-2025-05-14");
        }
        JsonNode toolsNode = requestBody == null ? null : requestBody.get("tools");
        if (toolsNode instanceof ArrayNode tools) {
            boolean toolSearchUsed = false;
            boolean programmaticToolCallingUsed = false;
            boolean inputExamplesUsed = false;
            for (JsonNode tool : tools) {
                String toolType = text(tool == null ? null : tool.get("type"));
                if (toolType.startsWith("computer_20")) {
                    inject(seen, mutable, "computer-use-2025-11-24");
                }
                if (isToolSearchType(toolType)) {
                    toolSearchUsed = true;
                }
                if (hasCodeExecutionAllowedCallers(tool)) {
                    programmaticToolCallingUsed = true;
                }
                if (hasInputExamples(tool)) {
                    inputExamplesUsed = true;
                }
            }
            if (programmaticToolCallingUsed || inputExamplesUsed) {
                inject(seen, mutable, "advanced-tool-use-2025-11-20");
            }
            if (toolSearchUsed && modelSupportsToolSearch(modelId)) {
                if (!programmaticToolCallingUsed && !inputExamplesUsed) {
                    inject(seen, mutable, "tool-search-tool-2025-10-19");
                } else {
                    inject(seen, mutable, "advanced-tool-use-2025-11-20");
                }
            }
        }
        return mutable;
    }

    public List<String> filterBetaTokens(List<String> tokens) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : tokens) {
            String token = "advanced-tool-use-2025-11-20".equals(raw)
                    ? "tool-search-tool-2025-10-19"
                    : raw;
            if (SUPPORTED_BETA_TOKENS.contains(token) && seen.add(token)) {
                result.add(token);
            }
        }
        if (seen.contains("tool-search-tool-2025-10-19") && seen.add("tool-examples-2025-10-29")) {
            result.add("tool-examples-2025-10-29");
        }
        return result;
    }

    public void inlineOutputFormatSchema(ObjectNode requestBody) {
        if (requestBody == null) {
            return;
        }
        JsonNode outputFormatNode = requestBody.get("output_format");
        if (!(outputFormatNode instanceof ObjectNode outputFormat)) {
            return;
        }
        requestBody.remove("output_format");
        JsonNode schema = outputFormat.get("schema");
        if (schema == null || schema.isNull()) {
            return;
        }
        JsonNode messagesNode = requestBody.get("messages");
        if (!(messagesNode instanceof ArrayNode messages)) {
            return;
        }
        int lastUserIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("user".equals(text(message == null ? null : message.get("role")))) {
                lastUserIndex = i;
                break;
            }
        }
        if (lastUserIndex < 0) {
            return;
        }
        JsonNode targetMessageNode = messages.get(lastUserIndex);
        if (!(targetMessageNode instanceof ObjectNode targetMessage)) {
            return;
        }
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            JsonNode contentNode = targetMessage.get("content");
            if (contentNode instanceof ArrayNode content) {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "text");
                block.put("text", schemaJson);
                content.add(block);
                return;
            }
            if (contentNode != null && contentNode.isTextual()) {
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode original = objectMapper.createObjectNode();
                original.put("type", "text");
                original.put("text", contentNode.asText());
                ObjectNode appended = objectMapper.createObjectNode();
                appended.put("type", "text");
                appended.put("text", schemaJson);
                content.add(original);
                content.add(appended);
                targetMessage.set("content", content);
            }
        } catch (JsonProcessingException ignored) {
        }
    }

    public void removeCustomFieldFromTools(ObjectNode requestBody) {
        if (requestBody == null) {
            return;
        }
        JsonNode toolsNode = requestBody.get("tools");
        if (!(toolsNode instanceof ArrayNode tools)) {
            return;
        }
        for (JsonNode toolNode : tools) {
            if (toolNode instanceof ObjectNode tool) {
                tool.remove("custom");
            }
        }
    }

    public void sanitizeCacheControl(ObjectNode requestBody, String modelId) {
        if (requestBody == null) {
            return;
        }
        boolean claude45OrNewer = isClaude45OrNewer(modelId);
        JsonNode systemNode = requestBody.get("system");
        if (systemNode instanceof ArrayNode system) {
            for (JsonNode itemNode : system) {
                if (itemNode instanceof ObjectNode item) {
                    sanitizeCacheControlNode(item.get("cache_control"), claude45OrNewer);
                }
            }
        }
        JsonNode messagesNode = requestBody.get("messages");
        if (!(messagesNode instanceof ArrayNode messages)) {
            return;
        }
        for (JsonNode messageNode : messages) {
            if (!(messageNode instanceof ObjectNode message)) {
                continue;
            }
            JsonNode contentNode = message.get("content");
            if (!(contentNode instanceof ArrayNode content)) {
                continue;
            }
            for (JsonNode blockNode : content) {
                if (blockNode instanceof ObjectNode block) {
                    sanitizeCacheControlNode(block.get("cache_control"), claude45OrNewer);
                }
            }
        }
    }

    public boolean isClaude45OrNewer(String modelId) {
        String normalized = trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        Matcher matcher = CLAUDE_VERSION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > 4 || (major == 4 && minor >= 5);
    }

    public boolean modelSupportsToolSearch(String modelId) {
        String normalized = trimToEmpty(modelId).toLowerCase(Locale.ROOT);
        if (normalized.contains("haiku")) {
            return false;
        }
        return isClaude45OrNewer(normalized);
    }

    private void sanitizeCacheControlNode(JsonNode cacheControlNode, boolean claude45OrNewer) {
        if (!(cacheControlNode instanceof ObjectNode cacheControl)) {
            return;
        }
        cacheControl.remove("scope");
        JsonNode ttl = cacheControl.get("ttl");
        if (ttl == null || ttl.isNull()) {
            return;
        }
        String ttlValue = ttl.asText();
        boolean allowed = claude45OrNewer && ("5m".equals(ttlValue) || "1h".equals(ttlValue));
        if (!allowed) {
            cacheControl.remove("ttl");
        }
    }

    private boolean isToolSearchType(String toolType) {
        return "tool_search_tool_regex_20251119".equals(toolType) || "tool_search_tool_bm25_20251119".equals(toolType);
    }

    private boolean hasCodeExecutionAllowedCallers(JsonNode toolNode) {
        return containsString(toolNode == null ? null : toolNode.get("allowed_callers"), "code_execution_20250825")
                || containsString(toolNode == null ? null : toolNode.path("function").get("allowed_callers"), "code_execution_20250825");
    }

    private boolean hasInputExamples(JsonNode toolNode) {
        return hasNonEmptyArray(toolNode == null ? null : toolNode.get("input_examples"))
                || hasNonEmptyArray(toolNode == null ? null : toolNode.path("function").get("input_examples"));
    }

    private boolean containsString(JsonNode arrayNode, String target) {
        if (!(arrayNode instanceof ArrayNode array)) {
            return false;
        }
        for (JsonNode item : array) {
            if (target.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNonEmptyArray(JsonNode arrayNode) {
        return arrayNode instanceof ArrayNode array && !array.isEmpty();
    }

    private void inject(Set<String> seen, List<String> tokens, String token) {
        if (seen.add(token)) {
            tokens.add(token);
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : trimToEmpty(node.asText());
    }
}
