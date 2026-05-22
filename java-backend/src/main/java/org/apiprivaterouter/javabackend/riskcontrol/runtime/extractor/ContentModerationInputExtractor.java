package org.apiprivaterouter.javabackend.riskcontrol.runtime.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Component
public class ContentModerationInputExtractor {

    public static final String PROTOCOL_ANTHROPIC_MESSAGES = "anthropic_messages";
    public static final String PROTOCOL_OPENAI_RESPONSES = "openai_responses";
    public static final String PROTOCOL_OPENAI_CHAT = "openai_chat_completions";
    public static final String PROTOCOL_GEMINI = "gemini";
    public static final String PROTOCOL_OPENAI_IMAGES = "openai_images";

    private final ObjectMapper objectMapper;

    public ContentModerationInputExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ContentModerationInput extract(String protocol, byte[] body) {
        if (body == null || body.length == 0) {
            return new ContentModerationInput("", List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            List<String> parts = new ArrayList<>();
            List<String> images = new ArrayList<>();
            switch (protocol == null ? "" : protocol) {
                case PROTOCOL_ANTHROPIC_MESSAGES -> collectLastAnthropicUserMessage(root.path("messages"), parts, images);
                case PROTOCOL_OPENAI_CHAT -> collectLastRoleMessage(root.path("messages"), "user", parts, images);
                case PROTOCOL_OPENAI_RESPONSES -> collectLastResponsesInput(root.path("input"), parts, images);
                case PROTOCOL_GEMINI -> collectLastGeminiContent(root.path("contents"), parts, images);
                case PROTOCOL_OPENAI_IMAGES -> {
                    addText(parts, root.path("prompt").asText());
                    collectContentValue(root.path("images"), parts, images);
                }
                default -> {
                    collectLastResponsesInput(root.path("input"), parts, images);
                    collectLastRoleMessage(root.path("messages"), "user", parts, images);
                    collectLastGeminiContent(root.path("contents"), parts, images);
                }
            }
            return new ContentModerationInput(String.join("\n", parts), images).normalize();
        } catch (Exception ignored) {
            return new ContentModerationInput("", List.of());
        }
    }

    private void collectLastRoleMessage(JsonNode messages, String role, List<String> parts, List<String> images) {
        if (!messages.isArray()) {
            return;
        }
        List<String> lastParts = List.of();
        List<String> lastImages = List.of();
        for (JsonNode msg : messages) {
            if (role.equalsIgnoreCase(msg.path("role").asText())) {
                List<String> candidateParts = new ArrayList<>();
                List<String> candidateImages = new ArrayList<>();
                collectContentValue(msg.path("content"), candidateParts, candidateImages);
                if (!candidateParts.isEmpty() || !candidateImages.isEmpty()) {
                    lastParts = candidateParts;
                    lastImages = candidateImages;
                }
            }
        }
        parts.addAll(lastParts);
        images.addAll(lastImages);
    }

    private void collectLastAnthropicUserMessage(JsonNode messages, List<String> parts, List<String> images) {
        if (!messages.isArray()) {
            return;
        }
        List<String> lastParts = List.of();
        List<String> lastImages = List.of();
        for (JsonNode msg : messages) {
            if ("user".equalsIgnoreCase(msg.path("role").asText())) {
                List<String> candidateParts = new ArrayList<>();
                List<String> candidateImages = new ArrayList<>();
                collectAnthropicContentValue(msg.path("content"), candidateParts, candidateImages);
                if (!candidateParts.isEmpty() || !candidateImages.isEmpty()) {
                    lastParts = candidateParts;
                    lastImages = candidateImages;
                }
            }
        }
        parts.addAll(lastParts);
        images.addAll(lastImages);
    }

    private void collectAnthropicContentValue(JsonNode value, List<String> parts, List<String> images) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (!text.trim().startsWith("<system-reminder>")) {
                addText(parts, text);
            }
            return;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                collectAnthropicContentValue(item, parts, images);
            }
            return;
        }
        String type = value.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || "text".equals(type) || "input_text".equals(type) || "message".equals(type)) {
            String text = value.path("text").asText("");
            if (!text.trim().startsWith("<system-reminder>")) {
                addText(parts, text);
            }
            collectAnthropicContentValue(value.path("content"), parts, images);
            return;
        }
        if ("image_url".equals(type) || "input_image".equals(type) || "image".equals(type)) {
            collectContentValue(value, parts, images);
        }
    }

    private void collectLastResponsesInput(JsonNode input, List<String> parts, List<String> images) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return;
        }
        if (input.isTextual()) {
            addText(parts, input.asText());
            return;
        }
        if (input.isObject()) {
            if (isResponsesUserItem(input)) {
                collectContentValue(input.path("content"), parts, images);
                if ("input_text".equals(input.path("type").asText()) || input.has("text")) {
                    collectContentValue(input, parts, images);
                }
            }
            return;
        }
        if (!input.isArray()) {
            return;
        }
        JsonNode last = null;
        for (JsonNode item : input) {
            if (isResponsesUserItem(item)) {
                last = item;
            }
        }
        if (last != null) {
            collectContentValue(last.path("content"), parts, images);
            if ("input_text".equals(last.path("type").asText()) || last.has("text")) {
                collectContentValue(last, parts, images);
            }
        }
    }

    private boolean isResponsesUserItem(JsonNode item) {
        String role = item.path("role").asText("").trim().toLowerCase(Locale.ROOT);
        if (!role.isEmpty() && !"user".equals(role)) {
            return false;
        }
        List<String> tmpParts = new ArrayList<>();
        List<String> tmpImages = new ArrayList<>();
        collectContentValue(item.path("content"), tmpParts, tmpImages);
        if ("input_text".equals(item.path("type").asText()) || item.has("text")) {
            collectContentValue(item, tmpParts, tmpImages);
        }
        return !tmpParts.isEmpty() || !tmpImages.isEmpty();
    }

    private void collectLastGeminiContent(JsonNode contents, List<String> parts, List<String> images) {
        if (!contents.isArray()) {
            return;
        }
        List<String> lastParts = List.of();
        List<String> lastImages = List.of();
        for (JsonNode content : contents) {
            String role = content.path("role").asText("").trim().toLowerCase(Locale.ROOT);
            if (role.isEmpty() || "user".equals(role)) {
                List<String> candidateParts = new ArrayList<>();
                List<String> candidateImages = new ArrayList<>();
                JsonNode nodes = content.path("parts");
                if (nodes.isArray()) {
                    for (JsonNode part : nodes) {
                        addText(candidateParts, part.path("text").asText());
                        addGeminiImage(candidateImages, part);
                    }
                }
                if (!candidateParts.isEmpty() || !candidateImages.isEmpty()) {
                    lastParts = candidateParts;
                    lastImages = candidateImages;
                }
            }
        }
        parts.addAll(lastParts);
        images.addAll(lastImages);
    }

    private void collectContentValue(JsonNode value, List<String> parts, List<String> images) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            addText(parts, value.asText());
            return;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                collectContentValue(item, parts, images);
            }
            return;
        }
        String type = value.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        addImage(images, value.path("image_url").path("url").asText());
        addImage(images, value.path("image_url").asText());
        addImage(images, value.path("url").asText());
        addInlineImage(images, value.path("source").path("media_type").asText(), value.path("source").path("data").asText());
        addInlineImage(images, value.path("source").path("mediaType").asText(), value.path("source").path("data").asText());
        addInlineImage(images, value.path("media_type").asText(), value.path("data").asText());
        addInlineImage(images, value.path("mime_type").asText(), value.path("data").asText());
        addInlineImage(images, value.path("mimeType").asText(), value.path("data").asText());
        addImage(images, value.path("source").path("data").asText());
        addImage(images, value.path("data").asText());
        addImage(images, value.path("base64").asText());
        if (type.isEmpty() || "text".equals(type) || "input_text".equals(type) || "message".equals(type)) {
            if (value.has("text")) {
                addText(parts, value.path("text").asText());
            }
            if (value.has("content")) {
                collectContentValue(value.path("content"), parts, images);
            }
        }
    }

    private void addGeminiImage(List<String> images, JsonNode part) {
        addInlineImage(images, part.path("inline_data").path("mime_type").asText(), part.path("inline_data").path("data").asText());
        addInlineImage(images, part.path("inlineData").path("mimeType").asText(), part.path("inlineData").path("data").asText());
        addImage(images, part.path("file_data").path("file_uri").asText());
        addImage(images, part.path("fileData").path("fileUri").asText());
    }

    private void addInlineImage(List<String> images, String mimeType, String data) {
        String normalizedMime = mimeType == null ? "" : mimeType.trim();
        String normalizedData = data == null ? "" : data.trim();
        if (!normalizedMime.isEmpty() && !normalizedData.isEmpty()) {
            addImage(images, "data:" + normalizedMime + ";base64," + normalizedData);
        }
    }

    private void addImage(List<String> images, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (normalized.startsWith("data:") || normalized.startsWith("http://") || normalized.startsWith("https://")) {
            images.add(normalized);
        }
    }

    private void addText(List<String> parts, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty() && !normalized.contains("<system-reminder>")) {
            parts.add(normalized);
        }
    }
}
