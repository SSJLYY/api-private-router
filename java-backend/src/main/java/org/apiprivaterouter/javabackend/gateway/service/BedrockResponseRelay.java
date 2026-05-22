package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BedrockResponseRelay {

    private final ObjectMapper objectMapper;

    public BedrockResponseRelay() {
        this(new ObjectMapper());
    }

    public BedrockResponseRelay(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> toSseEvents(List<BedrockEventStreamDecoder.BedrockEvent> events) throws IOException {
        List<String> result = new ArrayList<>();
        UsageAccumulator usage = new UsageAccumulator();
        for (BedrockEventStreamDecoder.BedrockEvent event : events) {
            if (event == null) {
                continue;
            }
            if (event.isException()) {
                result.add(writeSse("error", errorPayload(event)));
                continue;
            }
            if (!event.isChunk()) {
                continue;
            }
            JsonNode chunkJson = event.chunkJson();
            if (!(chunkJson instanceof ObjectNode objectNode)) {
                String raw = new String(event.chunkBytes(), StandardCharsets.UTF_8);
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("type", "message");
                payload.put("text", raw);
                result.add(writeSse("message", payload));
                continue;
            }

            usage.merge(extractUsage(objectNode));
            ObjectNode normalized = objectNode.deepCopy();
            ObjectNode usageNode = usage.toNode(objectMapper);
            if (hasNonZeroUsage(usageNode)) {
                normalized.set("usage", usageNode);
            }
            String eventName = text(normalized.get("type"));
            if (eventName.isEmpty()) {
                eventName = "message";
            }
            result.add(writeSse(eventName, normalized));
        }
        return result;
    }

    public ObjectNode toNormalizedJson(List<BedrockEventStreamDecoder.BedrockEvent> events) throws IOException {
        RelayState state = new RelayState();
        for (BedrockEventStreamDecoder.BedrockEvent event : events) {
            if (event == null) {
                continue;
            }
            if (event.isException()) {
                return errorPayload(event);
            }
            if (!event.isChunk()) {
                continue;
            }
            JsonNode chunkJson = event.chunkJson();
            if (!(chunkJson instanceof ObjectNode objectNode)) {
                String raw = new String(event.chunkBytes(), StandardCharsets.UTF_8);
                state.appendRawText(raw);
                continue;
            }
            state.usage.merge(extractUsage(objectNode));
            processChunk(state, objectNode);
        }
        state.finalizeOpenBlocks();
        return state.toResponse(objectMapper);
    }

    private void processChunk(RelayState state, ObjectNode event) throws IOException {
        String type = text(event.get("type"));
        switch (type) {
            case "message_start" -> handleMessageStart(state, event);
            case "content_block_start" -> handleContentBlockStart(state, event);
            case "content_block_delta" -> handleContentBlockDelta(state, event);
            case "content_block_stop" -> handleContentBlockStop(state, event);
            case "message_delta" -> handleMessageDelta(state, event);
            case "message_stop" -> {
            }
            default -> {
                if (event.has("metadata")) {
                    state.usage.merge(extractUsage(event.path("metadata")));
                }
            }
        }
    }

    private void handleMessageStart(RelayState state, ObjectNode event) {
        JsonNode message = event.get("message");
        if (!(message instanceof ObjectNode messageNode)) {
            return;
        }
        String id = text(messageNode.get("id"));
        if (!id.isEmpty()) {
            state.id = id;
        }
        String model = text(messageNode.get("model"));
        if (!model.isEmpty()) {
            state.model = model;
        }
        String role = text(messageNode.get("role"));
        if (!role.isEmpty()) {
            state.role = role;
        }
        state.usage.merge(extractUsage(messageNode));
    }

    private void handleContentBlockStart(RelayState state, ObjectNode event) {
        int index = event.path("index").asInt(state.content.size());
        JsonNode blockNode = event.get("content_block");
        ContentBlockState block = new ContentBlockState(blockNode instanceof ObjectNode objectNode ? objectNode.deepCopy() : objectMapper.createObjectNode());
        block.ensureBaseShape();
        state.putBlock(index, block);
    }

    private void handleContentBlockDelta(RelayState state, ObjectNode event) {
        int index = event.path("index").asInt(0);
        ContentBlockState block = state.ensureBlock(index);
        JsonNode deltaNode = event.get("delta");
        if (!(deltaNode instanceof ObjectNode delta)) {
            return;
        }
        String deltaType = text(delta.get("type"));
        switch (deltaType) {
            case "text_delta" -> block.appendText(text(delta.get("text")));
            case "thinking_delta" -> block.appendThinking(text(delta.get("thinking")));
            case "input_json_delta" -> {
                String partialJson = text(delta.get("partial_json"));
                if (partialJson.isEmpty()) {
                    partialJson = text(delta.get("partialJson"));
                }
                block.appendInputJson(partialJson);
            }
            case "signature_delta" -> block.node.put("signature", text(delta.get("signature")));
            default -> {
            }
        }
    }

    private void handleContentBlockStop(RelayState state, ObjectNode event) throws IOException {
        int index = event.path("index").asInt(0);
        ContentBlockState block = state.blocks.get(index);
        if (block != null) {
            block.finalizeBlock(objectMapper);
        }
    }

    private void handleMessageDelta(RelayState state, ObjectNode event) {
        JsonNode deltaNode = event.get("delta");
        if (deltaNode != null) {
            String stopReason = text(deltaNode.get("stop_reason"));
            if (!stopReason.isEmpty()) {
                state.stopReason = stopReason;
            }
            if (deltaNode.has("stop_sequence")) {
                state.stopSequence = deltaNode.get("stop_sequence");
            }
        }
        state.usage.merge(extractUsage(event));
    }

    private ObjectNode extractUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode usageNode = node.has("usage") ? node.get("usage") : null;
        JsonNode metricsNode = node.has("amazon-bedrock-invocationMetrics")
                ? node.get("amazon-bedrock-invocationMetrics")
                : node.get("invocationMetrics");

        int inputTokens = firstInt(
                usageNode, "input_tokens",
                usageNode, "inputTokens",
                metricsNode, "inputTokenCount"
        );
        int outputTokens = firstInt(
                usageNode, "output_tokens",
                usageNode, "outputTokens",
                metricsNode, "outputTokenCount"
        );
        int cacheCreationInputTokens = firstInt(
                usageNode, "cache_creation_input_tokens",
                usageNode, "cacheWriteInputTokens"
        );
        int cacheReadInputTokens = firstInt(
                usageNode, "cache_read_input_tokens",
                usageNode, "cacheReadInputTokens"
        );

        JsonNode inputDetails = usageNode == null ? null : usageNode.get("input_tokens_details");
        if (cacheReadInputTokens == 0 && inputDetails != null) {
            cacheReadInputTokens = intValue(inputDetails.get("cached_tokens"));
        }

        if (inputTokens == 0 && outputTokens == 0 && cacheCreationInputTokens == 0 && cacheReadInputTokens == 0) {
            return null;
        }

        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", Math.max(0, inputTokens));
        usage.put("output_tokens", Math.max(0, outputTokens));
        usage.put("cache_creation_input_tokens", Math.max(0, cacheCreationInputTokens));
        usage.put("cache_read_input_tokens", Math.max(0, cacheReadInputTokens));
        return usage;
    }

    private int firstInt(Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            JsonNode parent = (JsonNode) pairs[i];
            String field = (String) pairs[i + 1];
            if (parent == null || field == null) {
                continue;
            }
            JsonNode value = parent.get(field);
            if (value != null && !value.isNull()) {
                return intValue(value);
            }
        }
        return 0;
    }

    private int intValue(JsonNode node) {
        return node == null || node.isNull() ? 0 : node.asInt(0);
    }

    private boolean hasNonZeroUsage(ObjectNode usage) {
        return usage.path("input_tokens").asInt(0) > 0
                || usage.path("output_tokens").asInt(0) > 0
                || usage.path("cache_creation_input_tokens").asInt(0) > 0
                || usage.path("cache_read_input_tokens").asInt(0) > 0;
    }

    private String writeSse(String eventName, JsonNode payload) throws IOException {
        return "event: " + eventName + "\n" + "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
    }

    private ObjectNode errorPayload(BedrockEventStreamDecoder.BedrockEvent event) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "error");

        ObjectNode details = objectMapper.createObjectNode();
        details.put("type", event.exceptionType());
        JsonNode payload = event.exceptionPayload();
        String message = "";
        if (payload != null) {
            message = text(payload.get("message"));
            if (payload.has("originalStatusCode")) {
                details.put("original_status_code", payload.path("originalStatusCode").asInt());
            }
            if (payload.has("originalMessage")) {
                details.put("original_message", text(payload.get("originalMessage")));
            }
        }
        details.put("message", message);
        error.set("error", details);
        return error;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static final class RelayState {
        private final List<ContentBlockState> content = new ArrayList<>();
        private final Map<Integer, ContentBlockState> blocks = new HashMap<>();
        private final UsageAccumulator usage = new UsageAccumulator();
        private String id = "";
        private String model = "";
        private String role = "assistant";
        private String stopReason = "end_turn";
        private JsonNode stopSequence = null;

        private void appendRawText(String raw) {
            if (raw == null || raw.isEmpty()) {
                return;
            }
            ContentBlockState block = ensureBlock(content.size());
            block.node.put("type", "text");
            block.appendText(raw);
        }

        private void putBlock(int index, ContentBlockState block) {
            while (content.size() <= index) {
                content.add(null);
            }
            content.set(index, block);
            blocks.put(index, block);
        }

        private ContentBlockState ensureBlock(int index) {
            ContentBlockState block = blocks.get(index);
            if (block != null) {
                return block;
            }
            block = new ContentBlockState(new ObjectMapper().createObjectNode());
            block.node.put("type", "text");
            putBlock(index, block);
            return block;
        }

        private void finalizeOpenBlocks() throws IOException {
            for (ContentBlockState block : content) {
                if (block != null) {
                    block.finalizeBlock(new ObjectMapper());
                }
            }
        }

        private ObjectNode toResponse(ObjectMapper objectMapper) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("id", id);
            response.put("type", "message");
            response.put("role", role);
            response.put("model", model);

            ArrayNode contentNode = objectMapper.createArrayNode();
            for (ContentBlockState block : content) {
                if (block != null) {
                    contentNode.add(block.node);
                }
            }
            if (contentNode.isEmpty()) {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "text");
                block.put("text", "");
                contentNode.add(block);
            }
            response.set("content", contentNode);
            response.put("stop_reason", stopReason);
            if (stopSequence == null) {
                response.putNull("stop_sequence");
            } else {
                response.set("stop_sequence", stopSequence);
            }
            response.set("usage", usage.toNode(objectMapper));
            return response;
        }
    }

    private static final class ContentBlockState {
        private final ObjectNode node;
        private final StringBuilder inputJson = new StringBuilder();

        private ContentBlockState(ObjectNode node) {
            this.node = node;
        }

        private void ensureBaseShape() {
            String type = node.path("type").asText("");
            if ("text".equals(type) && !node.has("text")) {
                node.put("text", "");
            }
            if ("thinking".equals(type) && !node.has("thinking")) {
                node.put("thinking", "");
            }
            if ("tool_use".equals(type) && !node.has("input")) {
                node.putObject("input");
            }
        }

        private void appendText(String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            node.put("type", "text");
            node.put("text", node.path("text").asText("") + value);
        }

        private void appendThinking(String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            node.put("type", "thinking");
            node.put("thinking", node.path("thinking").asText("") + value);
        }

        private void appendInputJson(String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            node.put("type", "tool_use");
            inputJson.append(value);
        }

        private void finalizeBlock(ObjectMapper objectMapper) throws IOException {
            if (!"tool_use".equals(node.path("type").asText("")) || inputJson.length() == 0) {
                return;
            }
            JsonNode parsed = objectMapper.readTree(inputJson.toString());
            if (parsed != null && parsed.isObject()) {
                node.set("input", parsed);
            } else {
                node.put("input_text", inputJson.toString());
            }
        }
    }

    private static final class UsageAccumulator {
        private int inputTokens;
        private int outputTokens;
        private int cacheCreationInputTokens;
        private int cacheReadInputTokens;

        private void merge(ObjectNode usage) {
            if (usage == null) {
                return;
            }
            inputTokens = Math.max(inputTokens, usage.path("input_tokens").asInt(0));
            outputTokens = Math.max(outputTokens, usage.path("output_tokens").asInt(0));
            cacheCreationInputTokens = Math.max(cacheCreationInputTokens, usage.path("cache_creation_input_tokens").asInt(0));
            cacheReadInputTokens = Math.max(cacheReadInputTokens, usage.path("cache_read_input_tokens").asInt(0));
        }

        private ObjectNode toNode(ObjectMapper objectMapper) {
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("input_tokens", inputTokens);
            usage.put("output_tokens", outputTokens);
            usage.put("cache_creation_input_tokens", cacheCreationInputTokens);
            usage.put("cache_read_input_tokens", cacheReadInputTokens);
            return usage;
        }
    }
}
