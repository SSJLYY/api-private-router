package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GatewayGeminiMessagesCompatService {

    private static final String GEMINI_DUMMY_THOUGHT_SIGNATURE = "skip_thought_signature_validator";

    private final AdminAccountRepository accountRepository;
    private final GatewayGeminiProxyService geminiProxyService;
    private final GatewayUsageLoggingService usageLoggingService;
    private final ObjectMapper objectMapper;

    public GatewayGeminiMessagesCompatService(
            AdminAccountRepository accountRepository,
            GatewayGeminiProxyService geminiProxyService,
            GatewayUsageLoggingService usageLoggingService,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.geminiProxyService = geminiProxyService;
        this.usageLoggingService = usageLoggingService;
        this.objectMapper = objectMapper;
    }

    public boolean canHandle(GatewayRuntimeContext runtimeContext) {
        return runtimeContext != null
                && runtimeContext.account() != null
                && "gemini".equalsIgnoreCase(runtimeContext.account().platform())
                && geminiProxyService.canHandle(runtimeContext, false);
    }

    public void forward(
            GatewayRuntimeContext runtimeContext,
            HttpServletRequest request,
            HttpServletResponse response,
            byte[] body
    ) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            throw new AnthropicApiErrorException(503, "api_error", "No available Gemini accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new AnthropicApiErrorException(503, "api_error", "No available Gemini accounts"));
        if (!"gemini".equalsIgnoreCase(account.platform())) {
            throw new AnthropicApiErrorException(503, "api_error", "No available Gemini accounts");
        }

        ClaudeCompatRequest compatRequest = parseClaudeRequest(body);
        byte[] geminiBody = writeJsonBytes(convertClaudeRequestToGemini(compatRequest.root()));
        String action = compatRequest.stream() ? "streamGenerateContent" : "generateContent";
        CapturingHttpServletResponse captured = new CapturingHttpServletResponse(response);

        try {
            geminiProxyService.forward(
                    runtimeContext,
                    new AcceptHeaderRequest(request, compatRequest.stream() ? "text/event-stream" : "application/json"),
                    captured,
                    geminiBody,
                    compatRequest.model(),
                    action
            );
        } catch (ApiErrorException ex) {
            throw translateGeminiError(ex);
        }

        if (captured.status() >= 400) {
            throw translateGeminiError(captured.status(), captured.body());
        }
        if (compatRequest.stream()) {
            writeClaudeStreamingResponse(runtimeContext, response, captured.body(), compatRequest.model());
            return;
        }
        writeClaudeJsonResponse(runtimeContext, response, captured.body(), compatRequest.model());
    }

    private ClaudeCompatRequest parseClaudeRequest(byte[] body) {
        if (body == null || body.length == 0) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode root)) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            String model = text(root.get("model"));
            if (model.isBlank()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
            }
            if (!(root.get("messages") instanceof ArrayNode messages) || messages.isEmpty()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "messages must be a non-empty array");
            }
            return new ClaudeCompatRequest(root.deepCopy(), model, root.path("stream").asBoolean(false));
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private ObjectNode convertClaudeRequestToGemini(ObjectNode root) {
        ObjectNode out = objectMapper.createObjectNode();
        String systemText = extractSystemText(root.get("system"));
        if (!systemText.isBlank()) {
            ObjectNode systemInstruction = objectMapper.createObjectNode();
            ArrayNode parts = objectMapper.createArrayNode();
            ObjectNode part = objectMapper.createObjectNode();
            part.put("text", systemText);
            parts.add(part);
            systemInstruction.set("parts", parts);
            out.set("systemInstruction", systemInstruction);
        }

        Map<String, String> toolUseIdToName = new LinkedHashMap<>();
        out.set("contents", convertClaudeMessages(root.get("messages"), toolUseIdToName));

        ArrayNode tools = convertClaudeTools(root.get("tools"));
        if (tools != null && !tools.isEmpty()) {
            out.set("tools", tools);
        }

        ObjectNode generationConfig = convertGenerationConfig(root);
        if (generationConfig != null && !generationConfig.isEmpty()) {
            out.set("generationConfig", generationConfig);
        }

        normalizeGeminiTools(out);
        ensureFunctionCallThoughtSignatures(out);
        return out;
    }

    private ArrayNode convertClaudeMessages(JsonNode messagesNode, Map<String, String> toolUseIdToName) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!(messagesNode instanceof ArrayNode messages)) {
            return out;
        }
        for (JsonNode messageNode : messages) {
            if (!(messageNode instanceof ObjectNode message)) {
                continue;
            }
            ObjectNode content = objectMapper.createObjectNode();
            content.put("role", "assistant".equalsIgnoreCase(text(message.get("role"))) ? "model" : "user");
            ArrayNode parts = objectMapper.createArrayNode();
            JsonNode contentNode = message.get("content");
            if (contentNode != null && contentNode.isTextual()) {
                ObjectNode part = objectMapper.createObjectNode();
                part.put("text", contentNode.asText(""));
                parts.add(part);
            } else if (contentNode instanceof ArrayNode blocks) {
                boolean singleBlock = blocks.size() == 1;
                for (JsonNode blockNode : blocks) {
                    if (!(blockNode instanceof ObjectNode block)) {
                        continue;
                    }
                    String type = text(block.get("type"));
                    switch (type) {
                        case "text" -> {
                            String blockText = block.path("text").asText("");
                            if (singleBlock || !blockText.trim().isEmpty()) {
                                ObjectNode part = objectMapper.createObjectNode();
                                part.put("text", blockText);
                                parts.add(part);
                            }
                        }
                        case "tool_use" -> {
                            String id = text(block.get("id"));
                            String name = defaultIfBlank(text(block.get("name")), "tool");
                            if (!id.isBlank()) {
                                toolUseIdToName.put(id, name);
                            }
                            ObjectNode functionCall = objectMapper.createObjectNode();
                            functionCall.put("name", name);
                            JsonNode argsNode = block.get("input");
                            functionCall.set("args", argsNode == null || argsNode.isNull()
                                    ? objectMapper.createObjectNode()
                                    : argsNode.deepCopy());
                            ObjectNode part = objectMapper.createObjectNode();
                            part.put("thoughtSignature", GEMINI_DUMMY_THOUGHT_SIGNATURE);
                            part.set("functionCall", functionCall);
                            parts.add(part);
                        }
                        case "tool_result" -> {
                            String toolName = defaultIfBlank(toolUseIdToName.get(text(block.get("tool_use_id"))), "tool");
                            ObjectNode functionResponse = objectMapper.createObjectNode();
                            functionResponse.put("name", toolName);
                            ObjectNode response = objectMapper.createObjectNode();
                            response.put("content", extractClaudeContentText(block.get("content")));
                            functionResponse.set("response", response);
                            ObjectNode part = objectMapper.createObjectNode();
                            part.set("functionResponse", functionResponse);
                            parts.add(part);
                        }
                        case "image" -> {
                            JsonNode sourceNode = block.get("source");
                            if (sourceNode instanceof ObjectNode source && "base64".equals(text(source.get("type")))) {
                                String mediaType = text(source.get("media_type"));
                                String data = text(source.get("data"));
                                if (!mediaType.isBlank() && !data.isBlank()) {
                                    ObjectNode inlineData = objectMapper.createObjectNode();
                                    inlineData.put("mimeType", mediaType);
                                    inlineData.put("data", data);
                                    ObjectNode part = objectMapper.createObjectNode();
                                    part.set("inlineData", inlineData);
                                    parts.add(part);
                                }
                            }
                        }
                        default -> {
                            ObjectNode part = objectMapper.createObjectNode();
                            part.put("text", compactJson(block));
                            parts.add(part);
                        }
                    }
                }
            }
            content.set("parts", parts);
            out.add(content);
        }
        return out;
    }

    private ArrayNode convertClaudeTools(JsonNode toolsNode) {
        if (!(toolsNode instanceof ArrayNode tools) || tools.isEmpty()) {
            return null;
        }
        boolean hasWebSearch = false;
        ArrayNode functionDeclarations = objectMapper.createArrayNode();
        for (JsonNode toolNode : tools) {
            if (!(toolNode instanceof ObjectNode tool)) {
                continue;
            }
            if (isWebSearchTool(tool)) {
                hasWebSearch = true;
                continue;
            }
            String type = text(tool.get("type"));
            String name;
            String description;
            JsonNode parameters;
            if ("custom".equals(type) && tool.get("custom") instanceof ObjectNode custom) {
                name = text(tool.get("name"));
                description = text(custom.get("description"));
                parameters = custom.get("input_schema");
            } else {
                name = text(tool.get("name"));
                description = text(tool.get("description"));
                parameters = tool.get("input_schema");
            }
            if (name.isBlank()) {
                continue;
            }
            ObjectNode declaration = objectMapper.createObjectNode();
            declaration.put("name", name);
            if (!description.isBlank()) {
                declaration.put("description", description);
            }
            declaration.set("parameters", cleanToolSchema(parameters));
            functionDeclarations.add(declaration);
        }

        ArrayNode out = objectMapper.createArrayNode();
        if (!functionDeclarations.isEmpty()) {
            ObjectNode group = objectMapper.createObjectNode();
            group.set("functionDeclarations", functionDeclarations);
            out.add(group);
        }
        if (hasWebSearch) {
            ObjectNode webSearch = objectMapper.createObjectNode();
            webSearch.set("googleSearch", objectMapper.createObjectNode());
            out.add(webSearch);
        }
        return out.isEmpty() ? null : out;
    }

    private ObjectNode cleanToolSchema(JsonNode schemaNode) {
        if (!(schemaNode instanceof ObjectNode source)) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("type", "OBJECT");
            fallback.set("properties", objectMapper.createObjectNode());
            return fallback;
        }
        ObjectNode cleaned = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if ("$schema".equals(key) || "$id".equals(key) || "$ref".equals(key)
                    || "additionalProperties".equals(key) || "patternProperties".equals(key)
                    || "minLength".equals(key) || "maxLength".equals(key)
                    || "minItems".equals(key) || "maxItems".equals(key)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if ("type".equals(key) && value.isTextual()) {
                cleaned.put(key, value.asText("").toUpperCase(Locale.ROOT));
            } else if (value instanceof ObjectNode objectValue) {
                cleaned.set(key, cleanToolSchema(objectValue));
            } else {
                cleaned.set(key, value.deepCopy());
            }
        }
        return cleaned;
    }

    private ObjectNode convertGenerationConfig(ObjectNode root) {
        ObjectNode config = objectMapper.createObjectNode();
        if (root.has("max_tokens") && root.get("max_tokens").canConvertToInt()) {
            config.put("maxOutputTokens", Math.max(1, root.get("max_tokens").asInt()));
        }
        if (root.has("temperature")) {
            config.set("temperature", root.get("temperature").deepCopy());
        }
        if (root.has("top_p")) {
            config.set("topP", root.get("top_p").deepCopy());
        }
        if (root.get("stop_sequences") instanceof ArrayNode stopSequences && !stopSequences.isEmpty()) {
            config.set("stopSequences", stopSequences.deepCopy());
        }
        return config.isEmpty() ? null : config;
    }

    private void normalizeGeminiTools(ObjectNode root) {
        JsonNode toolsNode = root.get("tools");
        if (!(toolsNode instanceof ArrayNode tools)) {
            return;
        }
        for (JsonNode toolNode : tools) {
            if (toolNode instanceof ObjectNode tool && tool.get("googleSearch") != null && tool.get("google_search") == null) {
                tool.set("google_search", tool.get("googleSearch"));
                tool.remove("googleSearch");
            }
        }
    }

    private void ensureFunctionCallThoughtSignatures(ObjectNode root) {
        JsonNode contentsNode = root.get("contents");
        if (!(contentsNode instanceof ArrayNode contents)) {
            return;
        }
        for (JsonNode contentNode : contents) {
            if (!(contentNode instanceof ObjectNode content)) {
                continue;
            }
            JsonNode partsNode = content.get("parts");
            if (!(partsNode instanceof ArrayNode parts)) {
                continue;
            }
            for (JsonNode partNode : parts) {
                if (partNode instanceof ObjectNode part && part.get("functionCall") != null && text(part.get("thoughtSignature")).isBlank()) {
                    part.put("thoughtSignature", GEMINI_DUMMY_THOUGHT_SIGNATURE);
                }
            }
        }
    }

    private void writeClaudeJsonResponse(GatewayRuntimeContext runtimeContext, HttpServletResponse response, byte[] rawBody, String originalModel) {
        byte[] body = unwrapGeminiResponse(rawBody);
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode geminiResp)) {
                throw new AnthropicApiErrorException(502, "api_error", "Failed to parse upstream response");
            }
            ObjectNode output = convertGeminiToClaudeMessage(geminiResp, originalModel, body);
            ClaudeUsage usage = new ClaudeUsage();
            updateUsageFromGeminiResponse(body, usage);
            logUsageFromState(runtimeContext, originalModel, usage.inputTokens, usage.outputTokens, 0, usage.cacheReadInputTokens, false);
            response.setStatus(200);
            response.setContentType("application/json");
            response.getOutputStream().write(writeJsonBytes(output));
            response.flushBuffer();
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to write upstream response");
        }
    }

    private void writeClaudeStreamingResponse(GatewayRuntimeContext runtimeContext, HttpServletResponse response, byte[] rawBody, String originalModel) {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        ClaudeUsage usage = new ClaudeUsage();
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        int nextBlockIndex = 0;
        int openTextIndex = -1;
        int openToolIndex = -1;
        String seenText = "";
        String seenToolJson = "";
        String openToolName = "";
        String finishReason = "";
        boolean sawToolUse = false;

        try {
            ServletOutputStream output = response.getOutputStream();
            writeSse(output, "message_start", buildMessageStartEvent(messageId, originalModel));
            output.flush();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.io.ByteArrayInputStream(rawBody), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        continue;
                    }
                    String payload = trimmed.substring("data:".length()).trim();
                    if (payload.isBlank() || "[DONE]".equals(payload)) {
                        continue;
                    }
                    byte[] itemBody = unwrapGeminiResponse(payload.getBytes(StandardCharsets.UTF_8));
                    JsonNode node = objectMapper.readTree(itemBody);
                    if (!(node instanceof ObjectNode geminiResp)) {
                        continue;
                    }
                    updateUsageFromGeminiResponse(itemBody, usage);
                    String currentFinishReason = extractGeminiFinishReason(geminiResp);
                    if (!currentFinishReason.isBlank()) {
                        finishReason = currentFinishReason;
                    }
                    ArrayNode parts = extractGeminiParts(geminiResp);
                    for (JsonNode partNode : parts) {
                        if (!(partNode instanceof ObjectNode part)) {
                            continue;
                        }
                        String text = text(part.get("text"));
                        if (!text.isBlank()) {
                            String delta = computeDelta(seenText, text);
                            seenText = updateSeen(seenText, text);
                            if (delta.isBlank()) {
                                continue;
                            }
                            if (openToolIndex >= 0) {
                                writeSse(output, "content_block_stop", contentBlockStop(openToolIndex));
                                openToolIndex = -1;
                                seenToolJson = "";
                                openToolName = "";
                            }
                            if (openTextIndex < 0) {
                                openTextIndex = nextBlockIndex++;
                                writeSse(output, "content_block_start", textBlockStart(openTextIndex));
                            }
                            writeSse(output, "content_block_delta", textDelta(openTextIndex, delta));
                            output.flush();
                            continue;
                        }
                        if (part.get("functionCall") instanceof ObjectNode functionCall) {
                            String name = defaultIfBlank(text(functionCall.get("name")), "tool");
                            String argsJson = compactJson(functionCall.get("args"));
                            if (openTextIndex >= 0) {
                                writeSse(output, "content_block_stop", contentBlockStop(openTextIndex));
                                openTextIndex = -1;
                            }
                            if (openToolIndex >= 0 && !name.equals(openToolName)) {
                                writeSse(output, "content_block_stop", contentBlockStop(openToolIndex));
                                openToolIndex = -1;
                                seenToolJson = "";
                            }
                            if (openToolIndex < 0) {
                                openToolIndex = nextBlockIndex++;
                                openToolName = name;
                                sawToolUse = true;
                                writeSse(output, "content_block_start", toolUseBlockStart(openToolIndex, name));
                            }
                            String delta = computeDelta(seenToolJson, argsJson);
                            seenToolJson = updateSeen(seenToolJson, argsJson);
                            if (!delta.isBlank()) {
                                writeSse(output, "content_block_delta", inputJsonDelta(openToolIndex, delta));
                            }
                            output.flush();
                        }
                    }
                }
            }
            if (openTextIndex >= 0) {
                writeSse(output, "content_block_stop", contentBlockStop(openTextIndex));
            }
            if (openToolIndex >= 0) {
                writeSse(output, "content_block_stop", contentBlockStop(openToolIndex));
            }
            writeSse(output, "message_delta", messageDelta(sawToolUse ? "tool_use" : mapStopReason(finishReason), usage));
            writeSse(output, "message_stop", Map.of("type", "message_stop"));
            output.flush();
            logUsageFromState(runtimeContext, originalModel, usage.inputTokens, usage.outputTokens, 0, usage.cacheReadInputTokens, true);
            response.flushBuffer();
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to write upstream response");
        }
    }

    private ObjectNode convertGeminiToClaudeMessage(ObjectNode geminiResp, String originalModel, byte[] rawData) {
        ClaudeUsage usage = new ClaudeUsage();
        updateUsageFromGeminiResponse(rawData, usage);
        ArrayNode content = objectMapper.createArrayNode();
        boolean sawToolUse = false;
        for (JsonNode partNode : extractGeminiParts(geminiResp)) {
            if (!(partNode instanceof ObjectNode part)) {
                continue;
            }
            String text = text(part.get("text"));
            if (!text.isBlank()) {
                ObjectNode textBlock = objectMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", text);
                content.add(textBlock);
            }
            if (part.get("functionCall") instanceof ObjectNode functionCall) {
                ObjectNode toolUse = objectMapper.createObjectNode();
                toolUse.put("type", "tool_use");
                toolUse.put("id", "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                toolUse.put("name", defaultIfBlank(text(functionCall.get("name")), "tool"));
                JsonNode argsNode = functionCall.get("args");
                toolUse.set("input", argsNode == null || argsNode.isNull() ? objectMapper.createObjectNode() : argsNode.deepCopy());
                content.add(toolUse);
                sawToolUse = true;
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.put("type", "message");
        response.put("role", "assistant");
        response.put("model", originalModel);
        response.set("content", content);
        response.put("stop_reason", sawToolUse ? "tool_use" : mapStopReason(extractGeminiFinishReason(geminiResp)));
        response.putNull("stop_sequence");

        ObjectNode usageNode = objectMapper.createObjectNode();
        usageNode.put("input_tokens", usage.inputTokens);
        usageNode.put("output_tokens", usage.outputTokens);
        usageNode.put("cache_creation_input_tokens", 0);
        usageNode.put("cache_read_input_tokens", usage.cacheReadInputTokens);
        response.set("usage", usageNode);
        return response;
    }

    private ArrayNode extractGeminiParts(ObjectNode geminiResp) {
        if (!(geminiResp.get("candidates") instanceof ArrayNode candidates) || candidates.isEmpty()) {
            return objectMapper.createArrayNode();
        }
        JsonNode firstCandidate = candidates.get(0);
        if (!(firstCandidate instanceof ObjectNode candidate)) {
            return objectMapper.createArrayNode();
        }
        if (!(candidate.get("content") instanceof ObjectNode content)) {
            return objectMapper.createArrayNode();
        }
        return content.get("parts") instanceof ArrayNode parts ? parts : objectMapper.createArrayNode();
    }

    private String extractGeminiFinishReason(ObjectNode geminiResp) {
        if (!(geminiResp.get("candidates") instanceof ArrayNode candidates) || candidates.isEmpty()) {
            return "";
        }
        return text(candidates.get(0).get("finishReason"));
    }

    private void updateUsageFromGeminiResponse(byte[] rawData, ClaudeUsage usage) {
        try {
            JsonNode root = objectMapper.readTree(rawData);
            JsonNode usageNode = root.path("usageMetadata");
            if (usageNode.isMissingNode() || usageNode.isNull()) {
                return;
            }
            int prompt = usageNode.path("promptTokenCount").asInt(0);
            int candidates = usageNode.path("candidatesTokenCount").asInt(0);
            int cached = usageNode.path("cachedContentTokenCount").asInt(0);
            int thoughts = usageNode.path("thoughtsTokenCount").asInt(0);
            usage.inputTokens = Math.max(0, prompt - cached);
            usage.outputTokens = Math.max(0, candidates + thoughts);
            usage.cacheReadInputTokens = Math.max(0, cached);
        } catch (Exception ignored) {
        }
    }

    private byte[] unwrapGeminiResponse(byte[] raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode responseNode = root.get("response");
            return responseNode == null || responseNode.isNull() ? raw : objectMapper.writeValueAsBytes(responseNode);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String extractSystemText(JsonNode systemNode) {
        if (systemNode == null || systemNode.isNull()) {
            return "";
        }
        if (systemNode.isTextual()) {
            return systemNode.asText("").trim();
        }
        if (!(systemNode instanceof ArrayNode items)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : items) {
            if (item instanceof ObjectNode part && "text".equals(text(part.get("type")))) {
                String value = text(part.get("text"));
                if (!value.isBlank()) {
                    parts.add(value);
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    private String extractClaudeContentText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode instanceof ArrayNode parts) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : parts) {
                if (item instanceof ObjectNode part && "text".equals(text(part.get("type")))) {
                    builder.append(part.path("text").asText(""));
                }
            }
            return builder.toString();
        }
        return compactJson(contentNode);
    }

    private boolean isWebSearchTool(ObjectNode tool) {
        String type = text(tool.get("type"));
        if (type.startsWith("web_search") || "google_search".equals(type)) {
            return true;
        }
        String name = text(tool.get("name"));
        return "web_search".equals(name) || "google_search".equals(name) || "web_search_20250305".equals(name);
    }

    private String compactJson(JsonNode node) {
        try {
            return node == null ? "{}" : objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String computeDelta(String seen, String incoming) {
        String normalizedIncoming = incoming == null ? "" : incoming.replace("\u0000", "");
        if (normalizedIncoming.isBlank()) {
            return "";
        }
        if (normalizedIncoming.startsWith(seen)) {
            return normalizedIncoming.substring(seen.length());
        }
        if (seen.startsWith(normalizedIncoming)) {
            return "";
        }
        return normalizedIncoming;
    }

    private String updateSeen(String seen, String incoming) {
        String normalizedIncoming = incoming == null ? "" : incoming.replace("\u0000", "");
        if (normalizedIncoming.startsWith(seen)) {
            return normalizedIncoming;
        }
        if (seen.startsWith(normalizedIncoming)) {
            return seen;
        }
        return seen + normalizedIncoming;
    }

    private void writeSse(ServletOutputStream output, String event, Object payload) throws IOException {
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + objectMapper.writeValueAsString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> buildMessageStartEvent(String messageId, String model) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", List.of());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usage);
        return Map.of("type", "message_start", "message", message);
    }

    private Map<String, Object> textBlockStart(int index) {
        return Map.of(
                "type", "content_block_start",
                "index", index,
                "content_block", Map.of("type", "text", "text", "")
        );
    }

    private Map<String, Object> toolUseBlockStart(int index, String name) {
        return Map.of(
                "type", "content_block_start",
                "index", index,
                "content_block", Map.of(
                        "type", "tool_use",
                        "id", "toolu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                        "name", name,
                        "input", Map.of()
                )
        );
    }

    private Map<String, Object> textDelta(int index, String delta) {
        return Map.of(
                "type", "content_block_delta",
                "index", index,
                "delta", Map.of("type", "text_delta", "text", delta)
        );
    }

    private Map<String, Object> inputJsonDelta(int index, String delta) {
        return Map.of(
                "type", "content_block_delta",
                "index", index,
                "delta", Map.of("type", "input_json_delta", "partial_json", delta)
        );
    }

    private Map<String, Object> contentBlockStop(int index) {
        return Map.of("type", "content_block_stop", "index", index);
    }

    private Map<String, Object> messageDelta(String stopReason, ClaudeUsage usage) {
        Map<String, Object> usageNode = new LinkedHashMap<>();
        usageNode.put("output_tokens", usage.outputTokens);
        if (usage.inputTokens > 0) {
            usageNode.put("input_tokens", usage.inputTokens);
        }
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", stopReason);
        delta.put("stop_sequence", null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "message_delta");
        event.put("delta", delta);
        event.put("usage", usageNode);
        return event;
    }

    private String mapStopReason(String finishReason) {
        return "MAX_TOKENS".equalsIgnoreCase(defaultIfBlank(finishReason, "")) ? "max_tokens" : "end_turn";
    }

    private AnthropicApiErrorException translateGeminiError(int statusCode, byte[] body) {
        String reason = "";
        String message = "Gemini upstream request failed";
        if (body != null && body.length > 0) {
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode error = root == null ? null : root.get("error");
                String parsedMessage = text(error == null ? null : error.get("message"));
                if (parsedMessage.isBlank()) {
                    parsedMessage = text(root == null ? null : root.get("message"));
                }
                if (!parsedMessage.isBlank()) {
                    message = parsedMessage;
                }
                reason = defaultIfBlank(text(error == null ? null : error.get("status")), "");
            } catch (Exception ignored) {
            }
        }
        String errorType = switch (reason) {
            case "INVALID_ARGUMENT" -> "invalid_request_error";
            case "PERMISSION_DENIED" -> "permission_error";
            case "UNAUTHENTICATED" -> "authentication_error";
            case "DEADLINE_EXCEEDED" -> "timeout_error";
            case "RESOURCE_EXHAUSTED", "UNAVAILABLE" -> statusCode >= 500 ? "overloaded_error" : "api_error";
            default -> {
                if (statusCode == 401) {
                    yield "authentication_error";
                }
                if (statusCode == 403) {
                    yield "permission_error";
                }
                yield statusCode >= 500 ? "api_error" : "invalid_request_error";
            }
        };
        return new AnthropicApiErrorException(statusCode, errorType, message);
    }

    private AnthropicApiErrorException translateGeminiError(ApiErrorException ex) {
        String reason = defaultIfBlank(ex.getReason(), "");
        String errorType = switch (reason) {
            case "INVALID_ARGUMENT" -> "invalid_request_error";
            case "PERMISSION_DENIED" -> "permission_error";
            case "UNAUTHENTICATED" -> "authentication_error";
            case "DEADLINE_EXCEEDED" -> "timeout_error";
            case "UNAVAILABLE" -> ex.getStatus() >= 500 ? "overloaded_error" : "api_error";
            default -> ex.getStatus() >= 500 ? "api_error" : "invalid_request_error";
        };
        return new AnthropicApiErrorException(ex.getStatus(), errorType, ex.getMessage());
    }

    private byte[] writeJsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new HttpStatusException(500, "failed to encode response body");
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void logUsageFromState(GatewayRuntimeContext ctx, String model, int inputTokens, int outputTokens, int cacheCreationTokens, int cacheReadTokens, boolean stream) {
        if (ctx == null) {
            return;
        }
        try {
            usageLoggingService.logUsage(ctx, model, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, stream, null);
        } catch (Exception ex) {
            // usage logging should not break the response flow
        }
    }

    private record ClaudeCompatRequest(ObjectNode root, String model, boolean stream) {
    }

    private static final class ClaudeUsage {
        private int inputTokens;
        private int outputTokens;
        private int cacheReadInputTokens;
    }

    private static final class AcceptHeaderRequest extends HttpServletRequestWrapper {
        private final String accept;

        private AcceptHeaderRequest(HttpServletRequest delegate, String accept) {
            super(delegate);
            this.accept = accept;
        }

        @Override
        public String getHeader(String name) {
            if ("accept".equalsIgnoreCase(name)) {
                return accept;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("accept".equalsIgnoreCase(name)) {
                return java.util.Collections.enumeration(List.of(accept));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>(java.util.Collections.list(super.getHeaderNames()));
            names.add("Accept");
            return java.util.Collections.enumeration(names);
        }
    }

    private static final class CapturingHttpServletResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = HttpServletResponse.SC_OK;
        private String characterEncoding = StandardCharsets.UTF_8.name();
        private String contentType;
        private final ServletOutputStream outputStream = new ServletOutputStream() {
            @Override
            public void write(int b) {
                body.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(jakarta.servlet.WriteListener writeListener) {
            }
        };

        private CapturingHttpServletResponse(HttpServletResponse delegate) {
            super(delegate);
        }

        byte[] body() {
            return body.toByteArray();
        }

        int status() {
            return status;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            return new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);
        }

        @Override
        public void flushBuffer() {
        }

        @Override
        public int getBufferSize() {
            return body.size();
        }

        @Override
        public void resetBuffer() {
            body.reset();
        }

        @Override
        public void reset() {
            super.reset();
            body.reset();
            status = HttpServletResponse.SC_OK;
            contentType = null;
            characterEncoding = StandardCharsets.UTF_8.name();
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            this.status = sc;
        }

        @Override
        public void setCharacterEncoding(String charset) {
            if (charset != null && !charset.isBlank()) {
                this.characterEncoding = charset;
            }
        }

        @Override
        public String getCharacterEncoding() {
            return characterEncoding;
        }

        @Override
        public void setContentType(String type) {
            this.contentType = type;
        }

        @Override
        public String getContentType() {
            return contentType;
        }
    }
}
