package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GatewayAnthropicResponsesService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminAccountRepository accountRepository;
    private final GatewayAnthropicMessagesService anthropicMessagesService;
    private final GatewayAnthropicBedrockService bedrockService;
    private final ObjectMapper objectMapper;

    public GatewayAnthropicResponsesService(
            AdminAccountRepository accountRepository,
            GatewayAnthropicMessagesService anthropicMessagesService,
            GatewayAnthropicBedrockService bedrockService,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.anthropicMessagesService = anthropicMessagesService;
        this.bedrockService = bedrockService;
        this.objectMapper = objectMapper;
    }

    public boolean supportsPlatform(String platform) {
        return anthropicMessagesService.supportsPlatform(platform);
    }

    public void forward(GatewayRuntimeContext runtimeContext, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new OpenAiApiErrorException(503, "api_error", "No available compatible accounts"));
        if (!supportsPlatform(account.platform())) {
            throw new OpenAiApiErrorException(404, "not_found_error", "Responses API is not supported for this platform");
        }
        String accountType = normalize(account.type());
        PreparedRequest prepared = prepareRequest(body, account);
        if (bedrockService.canHandle(account)) {
            forwardBedrock(account, request, response, prepared);
            return;
        }
        if (!"apikey".equals(accountType)
                && !"oauth".equals(accountType)
                && !"setup-token".equals(accountType)
                && !("service_account".equals(accountType) && "anthropic".equalsIgnoreCase(account.platform()))) {
            throw new OpenAiApiErrorException(501, "unsupported_error", "Responses forwarding is not supported for this account type yet");
        }
        HttpRequest upstreamRequest = anthropicMessagesService.buildForwardRequest(
                account,
                request,
                prepared.anthropicBody(),
                true,
                prepared.mappedModel()
        );
        HttpResponse<InputStream> upstream = anthropicMessagesService.sendForwardRequest(account, upstreamRequest);
        throwIfFailoverRequired(upstream);
        if (upstream.statusCode() >= 400) {
            throw translateUpstreamError(readBufferedUpstreamError(upstream));
        }
        if (prepared.clientStream()) {
            streamResponse(response, upstream, prepared.originalModel());
            return;
        }
        writeBufferedResponse(response, upstream, prepared.originalModel());
    }

    private void forwardBedrock(
            AdminAccountResponse account,
            HttpServletRequest request,
            HttpServletResponse response,
            PreparedRequest prepared
    ) {
        GatewayAnthropicBedrockService.PreparedBedrockRequest bedrockRequest = bedrockService.prepareMessagesRequest(
                account,
                prepared.anthropicBody(),
                request == null ? null : request.getHeader("anthropic-beta")
        );
        HttpRequest upstreamRequest = bedrockService.buildRequest(account, bedrockRequest);
        HttpResponse<InputStream> upstream = bedrockService.send(account, upstreamRequest);
        if (prepared.clientStream()) {
            bedrockService.writeResponsesStream(response, upstream);
            return;
        }
        ObjectNode payload = bedrockService.readResponsesPayload(upstream);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(objectMapper.writeValueAsString(toResponsesJson(payload, prepared.originalModel())));
            response.flushBuffer();
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(500, "server_error", "Failed to write responses payload");
        }
    }

    private PreparedRequest prepareRequest(byte[] body, AdminAccountResponse account) {
        if (body == null || body.length == 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode root)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            String originalModel = requireModel(root);
            boolean clientStream = root.path("stream").asBoolean(false);
            String mappedModel = anthropicMessagesService.resolveForwardModel(account, originalModel);
            rejectUnsupportedRequestFields(root);

            ObjectNode anthropicRequest = objectMapper.createObjectNode();
            anthropicRequest.put("model", mappedModel);
            anthropicRequest.put("stream", true);
            int maxOutputTokens = root.path("max_output_tokens").asInt(0);
            anthropicRequest.put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : 8192);
            copyIfPresent(root, anthropicRequest, "temperature");
            copyIfPresent(root, anthropicRequest, "top_p");

            JsonNode systemNode = extractSystemNode(root);
            if (systemNode != null && !systemNode.isNull()) {
                anthropicRequest.set("system", systemNode);
            }
            anthropicRequest.set("messages", convertInput(root.get("input")));
            if (root.get("tools") instanceof ArrayNode tools && !tools.isEmpty()) {
                anthropicRequest.set("tools", convertTools(tools));
            }
            JsonNode toolChoice = convertToolChoice(root.get("tool_choice"));
            if (toolChoice != null && !toolChoice.isNull()) {
                anthropicRequest.set("tool_choice", toolChoice);
            }
            applyReasoning(root.get("reasoning"), anthropicRequest);

            return new PreparedRequest(
                    objectMapper.writeValueAsBytes(anthropicRequest),
                    clientStream,
                    originalModel,
                    mappedModel
            );
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private void rejectUnsupportedRequestFields(ObjectNode root) {
        String previousResponseId = text(root.get("previous_response_id"));
        if (!previousResponseId.isEmpty()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "previous_response_id is not supported for Anthropic responses over HTTP");
        }
    }

    private String requireModel(ObjectNode root) {
        JsonNode model = root == null ? null : root.get("model");
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "model is required");
        }
        return model.asText().trim();
    }

    private void copyIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode value = source == null ? null : source.get(fieldName);
        if (value != null && !value.isNull()) {
            target.set(fieldName, value.deepCopy());
        }
    }

    private JsonNode extractSystemNode(ObjectNode root) {
        if (root == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        String instructions = text(root.get("instructions"));
        if (!instructions.isEmpty()) {
            parts.add(instructions);
        }
        JsonNode inputNode = root.get("input");
        if (!(inputNode instanceof ArrayNode items)) {
            return parts.isEmpty()
                    ? null
                    : objectMapper.getNodeFactory().textNode(String.join("\n\n", parts));
        }
        for (JsonNode itemNode : items) {
            String role = normalizeRole(text(itemNode.get("role")));
            if (!"system".equals(role) && !"developer".equals(role)) {
                continue;
            }
            String extracted = extractText(itemNode.get("content"));
            if (!extracted.isBlank()) {
                parts.add(extracted);
            }
        }
        return parts.isEmpty()
                ? null
                : objectMapper.getNodeFactory().textNode(String.join("\n\n", parts));
    }

    private ArrayNode convertInput(JsonNode inputNode) {
        if (inputNode == null || inputNode.isNull()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "input is required");
        }
        ArrayNode messages = objectMapper.createArrayNode();
        if (inputNode.isTextual()) {
            messages.add(buildMessage("user", singleTextBlock(inputNode.asText())));
            return messages;
        }
        if (!(inputNode instanceof ArrayNode items)) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "input must be a string or array");
        }
        for (JsonNode itemNode : items) {
            if (!(itemNode instanceof ObjectNode item)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "input items must be objects");
            }
            String type = text(item.get("type"));
            String role = normalizeRole(text(item.get("role")));
            if ("system".equals(role) || "developer".equals(role)) {
                continue;
            }
            if ("reasoning".equals(type) || "item_reference".equals(type)) {
                continue;
            }
            if ("function_call".equals(type)) {
                messages.add(buildMessage("assistant", singleToolUseBlock(item)));
                continue;
            }
            if ("function_call_output".equals(type)) {
                messages.add(buildMessage("user", singleToolResultBlock(item)));
                continue;
            }
            JsonNode contentNode = item.get("content");
            if ("assistant".equals(role)) {
                messages.add(buildMessage("assistant", convertAssistantContent(contentNode)));
                continue;
            }
            if (!type.isEmpty() && contentNode == null) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Unsupported responses input item type: " + type);
            }
            messages.add(buildMessage("user", convertUserContent(contentNode)));
        }
        return mergeConsecutiveMessages(messages);
    }

    private ObjectNode buildMessage(String role, ArrayNode content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.set("content", content == null || content.isEmpty() ? singleTextBlock("") : content);
        return message;
    }

    private ArrayNode mergeConsecutiveMessages(ArrayNode messages) {
        ArrayNode merged = objectMapper.createArrayNode();
        for (JsonNode node : messages) {
            if (!(node instanceof ObjectNode message)) {
                continue;
            }
            if (merged.isEmpty()) {
                merged.add(message);
                continue;
            }
            ObjectNode previous = (ObjectNode) merged.get(merged.size() - 1);
            if (!text(previous.get("role")).equals(text(message.get("role")))) {
                merged.add(message);
                continue;
            }
            ArrayNode previousContent = previous.get("content") instanceof ArrayNode array ? array : objectMapper.createArrayNode();
            ArrayNode content = message.get("content") instanceof ArrayNode array ? array : objectMapper.createArrayNode();
            for (JsonNode block : content) {
                previousContent.add(block.deepCopy());
            }
            previous.set("content", previousContent);
        }
        return merged;
    }

    private ArrayNode convertUserContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return singleTextBlock("");
        }
        if (contentNode.isTextual()) {
            return singleTextBlock(contentNode.asText());
        }
        if (!(contentNode instanceof ArrayNode parts)) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "user content must be text or array");
        }
        ArrayNode content = objectMapper.createArrayNode();
        for (JsonNode partNode : parts) {
            if (!(partNode instanceof ObjectNode part)) {
                continue;
            }
            String type = text(part.get("type"));
            if ("input_text".equals(type) || "text".equals(type)) {
                String value = text(part.get("text"));
                if (!value.isEmpty()) {
                    content.add(textBlock(value));
                }
                continue;
            }
            if ("input_image".equals(type)) {
                String imageUrl = text(part.get("image_url"));
                ObjectNode source = parseDataUri(imageUrl);
                if (source != null) {
                    ObjectNode image = objectMapper.createObjectNode();
                    image.put("type", "image");
                    image.set("source", source);
                    content.add(image);
                    continue;
                }
                throw new OpenAiApiErrorException(400, "invalid_request_error", "input_image currently requires a data URI");
            }
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Unsupported user content part type: " + type);
        }
        return content.isEmpty() ? singleTextBlock("") : content;
    }

    private ArrayNode convertAssistantContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return singleTextBlock("");
        }
        if (contentNode.isTextual()) {
            return singleTextBlock(contentNode.asText());
        }
        if (!(contentNode instanceof ArrayNode parts)) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "assistant content must be text or array");
        }
        ArrayNode content = objectMapper.createArrayNode();
        for (JsonNode partNode : parts) {
            if (!(partNode instanceof ObjectNode part)) {
                continue;
            }
            String type = text(part.get("type"));
            if (!"output_text".equals(type) && !"text".equals(type)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Unsupported assistant content part type: " + type);
            }
            String value = text(part.get("text"));
            if (!value.isEmpty()) {
                content.add(textBlock(value));
            }
        }
        return content.isEmpty() ? singleTextBlock("") : content;
    }

    private ArrayNode singleTextBlock(String text) {
        ArrayNode content = objectMapper.createArrayNode();
        content.add(textBlock(text == null ? "" : text));
        return content;
    }

    private ObjectNode textBlock(String value) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "text");
        block.put("text", value == null ? "" : value);
        return block;
    }

    private ArrayNode singleToolUseBlock(ObjectNode item) {
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", normalizeCallId(text(item.get("call_id"))));
        block.put("name", text(item.get("name")));
        block.set("input", parseJsonOrText(text(item.get("arguments")), true));
        content.add(block);
        return content;
    }

    private ArrayNode singleToolResultBlock(ObjectNode item) {
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", normalizeCallId(text(item.get("call_id"))));
        String output = text(item.get("output"));
        block.put("content", output.isEmpty() ? "(empty)" : output);
        content.add(block);
        return content;
    }

    private String normalizeCallId(String callId) {
        String normalized = callId == null ? "" : callId.trim();
        if (normalized.startsWith("fc_toolu_") || normalized.startsWith("fc_call_")) {
            return normalized.substring(3);
        }
        if (!normalized.startsWith("toolu_") && !normalized.startsWith("call_") && !normalized.isEmpty()) {
            return "toolu_" + normalized;
        }
        return normalized;
    }

    private JsonNode parseJsonOrText(String raw, boolean emptyObjectFallback) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isEmpty()) {
            return emptyObjectFallback ? objectMapper.createObjectNode() : objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (IOException ignored) {
            return objectMapper.getNodeFactory().textNode(normalized);
        }
    }

    private ObjectNode parseDataUri(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("data:")) {
            return null;
        }
        int semicolon = imageUrl.indexOf(';');
        int comma = imageUrl.indexOf(',');
        if (semicolon <= "data:".length() || comma <= semicolon) {
            return null;
        }
        String mediaType = imageUrl.substring("data:".length(), semicolon).trim();
        String rest = imageUrl.substring(semicolon + 1, comma).trim().toLowerCase(Locale.ROOT);
        String data = imageUrl.substring(comma + 1).trim();
        if (!"base64".equals(rest) || mediaType.isEmpty() || data.isEmpty()) {
            return null;
        }
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", data);
        return source;
    }

    private String extractText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (!(contentNode instanceof ArrayNode parts)) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode partNode : parts) {
            String type = text(partNode.get("type"));
            if (!"input_text".equals(type) && !"output_text".equals(type) && !"text".equals(type)) {
                continue;
            }
            String value = text(partNode.get("text"));
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return String.join("\n\n", values);
    }

    private ArrayNode convertTools(ArrayNode tools) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode toolNode : tools) {
            if (!(toolNode instanceof ObjectNode tool)) {
                continue;
            }
            String type = text(tool.get("type"));
            if ("web_search".equals(type) || "google_search".equals(type) || "web_search_20250305".equals(type)) {
                ObjectNode webSearch = objectMapper.createObjectNode();
                webSearch.put("type", "web_search_20250305");
                webSearch.put("name", "web_search");
                result.add(webSearch);
                continue;
            }
            if (!"function".equals(type)) {
                continue;
            }
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", text(tool.get("name")));
            String description = text(tool.get("description"));
            if (!description.isEmpty()) {
                function.put("description", description);
            }
            JsonNode parameters = tool.get("parameters");
            function.set("input_schema", normalizeToolSchema(parameters));
            result.add(function);
        }
        return result;
    }

    private JsonNode normalizeToolSchema(JsonNode schemaNode) {
        if (!(schemaNode instanceof ObjectNode schema)) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", objectMapper.createObjectNode());
            return fallback;
        }
        ObjectNode normalized = schema.deepCopy();
        if (!"object".equals(text(normalized.get("type")))) {
            return normalized;
        }
        if (!(normalized.get("properties") instanceof ObjectNode)) {
            normalized.set("properties", objectMapper.createObjectNode());
        }
        return normalized;
    }

    private JsonNode convertToolChoice(JsonNode toolChoiceNode) {
        if (toolChoiceNode == null || toolChoiceNode.isNull()) {
            return null;
        }
        if (toolChoiceNode.isTextual()) {
            return switch (toolChoiceNode.asText().trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> objectNode("type", "auto");
                case "required" -> objectNode("type", "any");
                case "none" -> objectNode("type", "none");
                default -> toolChoiceNode.deepCopy();
            };
        }
        if (!(toolChoiceNode instanceof ObjectNode toolChoice)) {
            return toolChoiceNode.deepCopy();
        }
        String type = text(toolChoice.get("type"));
        if (!"function".equals(type)) {
            return toolChoice.deepCopy();
        }
        ObjectNode mapped = objectMapper.createObjectNode();
        mapped.put("type", "tool");
        mapped.put("name", text(toolChoice.get("name")));
        return mapped;
    }

    private ObjectNode objectNode(String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    private void applyReasoning(JsonNode reasoningNode, ObjectNode anthropicRequest) {
        if (!(reasoningNode instanceof ObjectNode reasoning)) {
            return;
        }
        String effort = text(reasoning.get("effort"));
        if (effort.isEmpty()) {
            return;
        }
        String mappedEffort = "xhigh".equals(effort) ? "max" : effort;
        ObjectNode outputConfig = objectMapper.createObjectNode();
        outputConfig.put("effort", mappedEffort);
        anthropicRequest.set("output_config", outputConfig);
        if ("low".equals(mappedEffort)) {
            return;
        }
        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", switch (mappedEffort) {
            case "medium" -> 4096;
            case "high" -> 10240;
            case "max" -> 32768;
            default -> 10240;
        });
        anthropicRequest.set("thinking", thinking);
    }

    private void throwIfFailoverRequired(HttpResponse<InputStream> upstream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status != 429 && status != 529) {
            return;
        }
        throw new OpenAiUpstreamFailoverException(status, "rate_limit_error", readUpstreamMessage(upstream));
    }

    private String readUpstreamMessage(HttpResponse<InputStream> upstream) {
        try (InputStream input = upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            return extractUpstreamMessage(body, "Anthropic upstream request failed");
        } catch (IOException ignored) {
            return "Anthropic upstream request failed";
        }
    }

    private BufferedUpstreamError readBufferedUpstreamError(HttpResponse<InputStream> upstream) {
        byte[] body = new byte[0];
        try (InputStream input = upstream.body()) {
            body = input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException ignored) {
        }
        return new BufferedUpstreamError(
                upstream == null ? 0 : upstream.statusCode(),
                body,
                extractUpstreamMessage(body, "Anthropic upstream request failed"),
                extractUpstreamErrorType(body)
        );
    }

    private String extractUpstreamMessage(byte[] body, String fallback) {
        if (body == null || body.length == 0) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isObject()) {
                String message = text(error.get("message"));
                if (!message.isEmpty()) {
                    return message;
                }
            }
        } catch (Exception ignored) {
        }
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        return raw.isEmpty() ? fallback : raw;
    }

    private String extractUpstreamErrorType(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isObject()) {
                return text(error.get("type"));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private OpenAiApiErrorException translateUpstreamError(BufferedUpstreamError upstreamError) {
        if (upstreamError.statusCode() >= 500) {
            return new OpenAiApiErrorException(502, "server_error", upstreamError.message());
        }
        if (upstreamError.statusCode() == 401 || upstreamError.statusCode() == 403) {
            return new OpenAiApiErrorException(upstreamError.statusCode(), "permission_error", upstreamError.message());
        }
        if ("not_found_error".equals(upstreamError.errorType())) {
            return new OpenAiApiErrorException(upstreamError.statusCode(), "not_found_error", upstreamError.message());
        }
        return new OpenAiApiErrorException(upstreamError.statusCode() <= 0 ? 502 : upstreamError.statusCode(), "invalid_request_error", upstreamError.message());
    }

    private void writeBufferedResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, String originalModel) {
        copyResponseHeaders(response, upstream);
        BufferedAnthropicResponse buffered = readBufferedAnthropicResponse(upstream);
        ObjectNode payload = toResponsesJson(buffered, originalModel);
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(objectMapper.writeValueAsString(payload));
            response.flushBuffer();
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(500, "server_error", "Failed to write responses payload");
        }
    }

    private BufferedAnthropicResponse readBufferedAnthropicResponse(HttpResponse<InputStream> upstream) {
        BufferedAnthropicResponse result = new BufferedAnthropicResponse();
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode eventNode = objectMapper.readTree(payload);
                if (!(eventNode instanceof ObjectNode event)) {
                    continue;
                }
                processBufferedEvent(result, event);
            }
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "server_error", "Failed to read Anthropic upstream response");
        }
        if (result.id.isBlank()) {
            throw new OpenAiApiErrorException(502, "server_error", "Upstream stream ended without a response");
        }
        return result;
    }

    private void processBufferedEvent(BufferedAnthropicResponse result, ObjectNode event) {
        String type = text(event.get("type"));
        if ("message_start".equals(type)) {
            JsonNode message = event.get("message");
            if (message instanceof ObjectNode messageNode) {
                result.id = text(messageNode.get("id"));
                result.model = text(messageNode.get("model"));
                result.inputTokens = Math.max(result.inputTokens, intValue(messageNode.path("usage").get("input_tokens")));
            }
            return;
        }
        if ("content_block_start".equals(type)) {
            int index = intValue(event.get("index"));
            ensureContentSize(result, index + 1);
            JsonNode block = event.get("content_block");
            if (block instanceof ObjectNode blockNode) {
                result.content.set(index, blockNode.deepCopy());
            }
            return;
        }
        if ("content_block_delta".equals(type)) {
            int index = intValue(event.get("index"));
            if (index < 0 || index >= result.content.size()) {
                return;
            }
            JsonNode delta = event.get("delta");
            if (!(delta instanceof ObjectNode deltaNode)) {
                return;
            }
            ObjectNode block = (ObjectNode) result.content.get(index);
            String deltaType = text(deltaNode.get("type"));
            if ("text_delta".equals(deltaType)) {
                block.put("text", text(block.get("text")) + text(deltaNode.get("text")));
                return;
            }
            if ("thinking_delta".equals(deltaType)) {
                block.put("thinking", text(block.get("thinking")) + text(deltaNode.get("thinking")));
                return;
            }
            if ("input_json_delta".equals(deltaType)) {
                result.inputJson.computeIfAbsent(index, ignored -> new StringBuilder())
                        .append(text(deltaNode.get("partial_json")));
            }
            return;
        }
        if ("message_delta".equals(type)) {
            JsonNode delta = event.get("delta");
            if (delta instanceof ObjectNode deltaNode) {
                String stopReason = text(deltaNode.get("stop_reason"));
                if (!stopReason.isEmpty()) {
                    result.stopReason = stopReason;
                }
            }
            JsonNode usage = event.get("usage");
            result.outputTokens = Math.max(result.outputTokens, intValue(usage == null ? null : usage.get("output_tokens")));
            result.cacheReadInputTokens = Math.max(result.cacheReadInputTokens, intValue(usage == null ? null : usage.get("cache_read_input_tokens")));
        }
    }

    private void ensureContentSize(BufferedAnthropicResponse result, int size) {
        while (result.content.size() < size) {
            ObjectNode placeholder = objectMapper.createObjectNode();
            placeholder.put("type", "text");
            placeholder.put("text", "");
            result.content.add(placeholder);
        }
    }

    private ObjectNode toResponsesJson(BufferedAnthropicResponse buffered, String originalModel) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", buffered.id.isBlank() ? randomId("resp_") : buffered.id);
        response.put("object", "response");
        response.put("model", originalModel);
        boolean hasToolUse = false;

        ArrayNode output = objectMapper.createArrayNode();
        ArrayNode assistantContent = objectMapper.createArrayNode();
        for (int i = 0; i < buffered.content.size(); i++) {
            JsonNode blockNode = buffered.content.get(i);
            if (!(blockNode instanceof ObjectNode block)) {
                continue;
            }
            String type = text(block.get("type"));
            if ("thinking".equals(type)) {
                String thinking = text(block.get("thinking"));
                if (thinking.isEmpty()) {
                    continue;
                }
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "reasoning");
                item.put("id", randomId("item_"));
                ArrayNode summary = item.putArray("summary");
                ObjectNode summaryText = objectMapper.createObjectNode();
                summaryText.put("type", "summary_text");
                summaryText.put("text", thinking);
                summary.add(summaryText);
                output.add(item);
                continue;
            }
            if ("text".equals(type)) {
                String value = text(block.get("text"));
                if (value.isEmpty()) {
                    continue;
                }
                ObjectNode part = objectMapper.createObjectNode();
                part.put("type", "output_text");
                part.put("text", value);
                assistantContent.add(part);
                continue;
            }
            if ("tool_use".equals(type)) {
                hasToolUse = true;
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "function_call");
                item.put("id", randomId("item_"));
                item.put("call_id", text(block.get("id")));
                item.put("name", text(block.get("name")));
                StringBuilder rawArguments = buffered.inputJson.get(i);
                String arguments = rawArguments == null ? compactJson(block.get("input")) : rawArguments.toString();
                if (arguments == null || arguments.isBlank() || "null".equals(arguments)) {
                    arguments = "{}";
                }
                item.put("arguments", arguments);
                item.put("status", "completed");
                output.add(item);
            }
        }
        if (!assistantContent.isEmpty()) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "message");
            message.put("id", randomId("item_"));
            message.put("role", "assistant");
            message.set("content", assistantContent);
            message.put("status", "completed");
            output.add(message);
        }
        if (output.isEmpty()) {
            ObjectNode emptyMessage = objectMapper.createObjectNode();
            emptyMessage.put("type", "message");
            emptyMessage.put("id", randomId("item_"));
            emptyMessage.put("role", "assistant");
            ArrayNode content = emptyMessage.putArray("content");
            ObjectNode part = objectMapper.createObjectNode();
            part.put("type", "output_text");
            part.put("text", "");
            content.add(part);
            emptyMessage.put("status", "completed");
            output.add(emptyMessage);
        }
        response.set("output", output);
        String status = "max_tokens".equals(buffered.stopReason) ? "incomplete" : "completed";
        response.put("status", status);
        if ("incomplete".equals(status)) {
            ObjectNode incompleteDetails = objectMapper.createObjectNode();
            incompleteDetails.put("reason", "max_output_tokens");
            response.set("incomplete_details", incompleteDetails);
        }

        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", buffered.inputTokens);
        usage.put("output_tokens", buffered.outputTokens);
        usage.put("total_tokens", buffered.inputTokens + buffered.outputTokens);
        if (buffered.cacheReadInputTokens > 0) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("cached_tokens", buffered.cacheReadInputTokens);
            usage.set("input_tokens_details", details);
        }
        response.set("usage", usage);
        if ("completed".equals(status) && hasToolUse) {
            response.put("status", "completed");
        }
        return response;
    }

    private ObjectNode toResponsesJson(ObjectNode anthropicMessage, String originalModel) {
        BufferedAnthropicResponse buffered = new BufferedAnthropicResponse();
        if (anthropicMessage == null) {
            return toResponsesJson(buffered, originalModel);
        }
        buffered.id = text(anthropicMessage.get("id"));
        buffered.model = text(anthropicMessage.get("model"));
        buffered.stopReason = text(anthropicMessage.get("stop_reason"));
        if (buffered.stopReason.isEmpty()) {
            buffered.stopReason = "end_turn";
        }
        JsonNode usage = anthropicMessage.get("usage");
        buffered.inputTokens = intValue(usage == null ? null : usage.get("input_tokens"));
        buffered.outputTokens = intValue(usage == null ? null : usage.get("output_tokens"));
        buffered.cacheReadInputTokens = intValue(usage == null ? null : usage.get("cache_read_input_tokens"));
        JsonNode contentNode = anthropicMessage.get("content");
        if (contentNode instanceof ArrayNode content) {
            for (JsonNode blockNode : content) {
                if (!(blockNode instanceof ObjectNode block)) {
                    continue;
                }
                int index = buffered.content.size();
                buffered.content.add(block.deepCopy());
                if ("tool_use".equals(text(block.get("type"))) && block.get("input") != null && !block.get("input").isNull()) {
                    buffered.inputJson.put(index, new StringBuilder(compactJson(block.get("input"))));
                }
            }
        }
        return toResponsesJson(buffered, originalModel);
    }

    private void streamResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, String originalModel) {
        copyResponseHeaders(response, upstream);
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        StreamingState state = new StreamingState(originalModel);
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            ServletOutputStream output = response.getOutputStream();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode eventNode = objectMapper.readTree(payload);
                if (!(eventNode instanceof ObjectNode event)) {
                    continue;
                }
                processStreamEvent(output, state, event);
            }
            finalizeStream(output, state);
            response.flushBuffer();
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "server_error", "Failed to stream responses payload");
        }
    }

    private void processStreamEvent(ServletOutputStream output, StreamingState state, ObjectNode event) throws IOException {
        String type = text(event.get("type"));
        switch (type) {
            case "message_start" -> handleMessageStart(output, state, event);
            case "content_block_start" -> handleContentBlockStart(output, state, event);
            case "content_block_delta" -> handleContentBlockDelta(output, state, event);
            case "content_block_stop" -> handleContentBlockStop(output, state);
            case "message_delta" -> handleMessageDelta(state, event);
            case "message_stop" -> handleMessageStop(output, state);
            default -> {
            }
        }
    }

    private void handleMessageStart(ServletOutputStream output, StreamingState state, ObjectNode event) throws IOException {
        JsonNode message = event.get("message");
        if (message instanceof ObjectNode messageNode) {
            state.responseId = text(messageNode.get("id"));
            if (state.model.isBlank()) {
                state.model = text(messageNode.get("model"));
            }
            state.inputTokens = Math.max(state.inputTokens, intValue(messageNode.path("usage").get("input_tokens")));
        }
        if (state.createdSent) {
            return;
        }
        state.createdSent = true;
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("id", state.responseId);
        responseNode.put("object", "response");
        responseNode.put("model", state.model);
        responseNode.put("status", "in_progress");
        responseNode.set("output", objectMapper.createArrayNode());

        ObjectNode payload = basePayload(state, "response.created");
        payload.set("response", responseNode);
        writeSse(output, "response.created", payload);
    }

    private void handleContentBlockStart(ServletOutputStream output, StreamingState state, ObjectNode event) throws IOException {
        JsonNode blockNode = event.get("content_block");
        if (!(blockNode instanceof ObjectNode block)) {
            return;
        }
        String type = text(block.get("type"));
        if ("thinking".equals(type)) {
            state.currentItemType = "reasoning";
            state.currentItemId = randomId("item_");
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "reasoning");
            item.put("id", state.currentItemId);
            writeOutputItemAdded(output, state, item);
            return;
        }
        if ("text".equals(type)) {
            if (!"message".equals(state.currentItemType)) {
                state.currentItemType = "message";
                state.currentItemId = randomId("item_");
                ObjectNode item = objectMapper.createObjectNode();
                item.put("type", "message");
                item.put("id", state.currentItemId);
                item.put("role", "assistant");
                item.put("status", "in_progress");
                writeOutputItemAdded(output, state, item);
            }
            return;
        }
        if ("tool_use".equals(type)) {
            closeCurrentItem(output, state);
            state.currentItemType = "function_call";
            state.currentItemId = randomId("item_");
            state.currentCallId = text(block.get("id"));
            state.currentToolName = text(block.get("name"));
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call");
            item.put("id", state.currentItemId);
            item.put("call_id", state.currentCallId);
            item.put("name", state.currentToolName);
            item.put("status", "in_progress");
            writeOutputItemAdded(output, state, item);
        }
    }

    private void handleContentBlockDelta(ServletOutputStream output, StreamingState state, ObjectNode event) throws IOException {
        JsonNode deltaNode = event.get("delta");
        if (!(deltaNode instanceof ObjectNode delta)) {
            return;
        }
        String type = text(delta.get("type"));
        if ("text_delta".equals(type)) {
            String value = text(delta.get("text"));
            if (value.isEmpty()) {
                return;
            }
            ObjectNode payload = basePayload(state, "response.output_text.delta");
            payload.put("output_index", state.outputIndex);
            payload.put("content_index", 0);
            payload.put("delta", value);
            payload.put("item_id", state.currentItemId);
            writeSse(output, "response.output_text.delta", payload);
            return;
        }
        if ("thinking_delta".equals(type)) {
            String value = text(delta.get("thinking"));
            if (value.isEmpty()) {
                return;
            }
            ObjectNode payload = basePayload(state, "response.reasoning_summary_text.delta");
            payload.put("output_index", state.outputIndex);
            payload.put("summary_index", 0);
            payload.put("delta", value);
            payload.put("item_id", state.currentItemId);
            writeSse(output, "response.reasoning_summary_text.delta", payload);
            return;
        }
        if ("input_json_delta".equals(type)) {
            String value = text(delta.get("partial_json"));
            if (value.isEmpty()) {
                return;
            }
            ObjectNode payload = basePayload(state, "response.function_call_arguments.delta");
            payload.put("output_index", state.outputIndex);
            payload.put("delta", value);
            payload.put("item_id", state.currentItemId);
            payload.put("call_id", state.currentCallId);
            payload.put("name", state.currentToolName);
            writeSse(output, "response.function_call_arguments.delta", payload);
        }
    }

    private void handleContentBlockStop(ServletOutputStream output, StreamingState state) throws IOException {
        if ("reasoning".equals(state.currentItemType)) {
            ObjectNode payload = basePayload(state, "response.reasoning_summary_text.done");
            payload.put("output_index", state.outputIndex);
            payload.put("summary_index", 0);
            payload.put("item_id", state.currentItemId);
            writeSse(output, "response.reasoning_summary_text.done", payload);
            closeCurrentItem(output, state);
            return;
        }
        if ("function_call".equals(state.currentItemType)) {
            ObjectNode payload = basePayload(state, "response.function_call_arguments.done");
            payload.put("output_index", state.outputIndex);
            payload.put("item_id", state.currentItemId);
            payload.put("call_id", state.currentCallId);
            payload.put("name", state.currentToolName);
            writeSse(output, "response.function_call_arguments.done", payload);
            closeCurrentItem(output, state);
            return;
        }
        if ("message".equals(state.currentItemType)) {
            ObjectNode payload = basePayload(state, "response.output_text.done");
            payload.put("output_index", state.outputIndex);
            payload.put("content_index", 0);
            payload.put("item_id", state.currentItemId);
            writeSse(output, "response.output_text.done", payload);
        }
    }

    private void handleMessageDelta(StreamingState state, ObjectNode event) {
        JsonNode usage = event.get("usage");
        state.outputTokens = Math.max(state.outputTokens, intValue(usage == null ? null : usage.get("output_tokens")));
        state.cacheReadInputTokens = Math.max(state.cacheReadInputTokens, intValue(usage == null ? null : usage.get("cache_read_input_tokens")));
    }

    private void handleMessageStop(ServletOutputStream output, StreamingState state) throws IOException {
        if (state.completedSent) {
            return;
        }
        closeCurrentItem(output, state);
        writeCompletedEvent(output, state);
        state.completedSent = true;
    }

    private void finalizeStream(ServletOutputStream output, StreamingState state) throws IOException {
        if (!state.createdSent || state.completedSent) {
            return;
        }
        closeCurrentItem(output, state);
        writeCompletedEvent(output, state);
        state.completedSent = true;
    }

    private void writeCompletedEvent(ServletOutputStream output, StreamingState state) throws IOException {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input_tokens", state.inputTokens);
        usage.put("output_tokens", state.outputTokens);
        usage.put("total_tokens", state.inputTokens + state.outputTokens);
        if (state.cacheReadInputTokens > 0) {
            ObjectNode details = objectMapper.createObjectNode();
            details.put("cached_tokens", state.cacheReadInputTokens);
            usage.set("input_tokens_details", details);
        }
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("id", state.responseId);
        responseNode.put("object", "response");
        responseNode.put("model", state.model);
        responseNode.put("status", "completed");
        responseNode.set("output", objectMapper.createArrayNode());
        responseNode.set("usage", usage);
        ObjectNode payload = basePayload(state, "response.completed");
        payload.set("response", responseNode);
        writeSse(output, "response.completed", payload);
    }

    private void writeOutputItemAdded(ServletOutputStream output, StreamingState state, ObjectNode item) throws IOException {
        ObjectNode payload = basePayload(state, "response.output_item.added");
        payload.put("output_index", state.outputIndex);
        payload.set("item", item);
        writeSse(output, "response.output_item.added", payload);
    }

    private void closeCurrentItem(ServletOutputStream output, StreamingState state) throws IOException {
        if (state.currentItemType.isBlank()) {
            return;
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", state.currentItemType);
        item.put("id", state.currentItemId);
        item.put("status", "completed");
        ObjectNode payload = basePayload(state, "response.output_item.done");
        payload.put("output_index", state.outputIndex);
        payload.set("item", item);
        writeSse(output, "response.output_item.done", payload);

        state.currentItemType = "";
        state.currentItemId = "";
        state.currentCallId = "";
        state.currentToolName = "";
        state.outputIndex++;
    }

    private ObjectNode basePayload(StreamingState state, String type) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", type);
        payload.put("sequence_number", state.sequence++);
        return payload;
    }

    private void writeSse(ServletOutputStream output, String eventName, ObjectNode payload) throws IOException {
        output.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + objectMapper.writeValueAsString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void copyResponseHeaders(HttpServletResponse response, HttpResponse<InputStream> upstream) {
        upstream.headers().map().forEach((name, values) -> {
            if (name == null) {
                return;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if ("connection".equals(lower)
                    || "keep-alive".equals(lower)
                    || "proxy-authenticate".equals(lower)
                    || "proxy-authorization".equals(lower)
                    || "te".equals(lower)
                    || "trailers".equals(lower)
                    || "transfer-encoding".equals(lower)
                    || "upgrade".equals(lower)
                    || "content-length".equals(lower)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }

    private String randomId(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(prefix.length() + bytes.length * 2);
        builder.append(prefix);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private String normalizeRole(String role) {
        return switch (normalize(role)) {
            case "system" -> "system";
            case "developer" -> "developer";
            case "assistant" -> "assistant";
            default -> "user";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText("").trim() : "";
    }

    private int intValue(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : 0;
    }

    private String compactJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private record PreparedRequest(byte[] anthropicBody, boolean clientStream, String originalModel, String mappedModel) {
    }

    private record BufferedUpstreamError(int statusCode, byte[] body, String message, String errorType) {
    }

    private static final class BufferedAnthropicResponse {
        private String id = "";
        private String model = "";
        private String stopReason = "end_turn";
        private int inputTokens;
        private int outputTokens;
        private int cacheReadInputTokens;
        private final ArrayNode content = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        private final Map<Integer, StringBuilder> inputJson = new LinkedHashMap<>();
    }

    private static final class StreamingState {
        private final String originalModel;
        private String responseId = "";
        private String model = "";
        private int sequence;
        private boolean createdSent;
        private boolean completedSent;
        private int outputIndex;
        private String currentItemId = "";
        private String currentItemType = "";
        private String currentCallId = "";
        private String currentToolName = "";
        private int inputTokens;
        private int outputTokens;
        private int cacheReadInputTokens;

        private StreamingState(String originalModel) {
            this.originalModel = originalModel == null ? "" : originalModel;
            this.model = this.originalModel;
        }
    }
}
