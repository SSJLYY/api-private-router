package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ContentModerationInput(
        String text,
        List<String> images
) {

    private static final int MAX_INPUT_RUNES = 12_000;

    public ContentModerationInput normalize() {
        String normalizedText = normalizeText(text);
        if (normalizedText.length() > MAX_INPUT_RUNES) {
            normalizedText = normalizedText.substring(0, MAX_INPUT_RUNES);
        }
        return new ContentModerationInput(normalizedText, normalizeImages(images));
    }

    public boolean isEmpty() {
        return (text == null || text.isBlank()) && (images == null || images.isEmpty());
    }

    public Object moderationInput() {
        if (images == null || images.isEmpty()) {
            return text == null || text.isBlank() ? "hello" : text;
        }
        List<java.util.Map<String, Object>> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(java.util.Map.of(
                    "type", "text",
                    "text", text
            ));
        }
        for (String image : images) {
            parts.add(java.util.Map.of(
                    "type", "image_url",
                    "image_url", java.util.Map.of("url", image)
            ));
        }
        return parts;
    }

    public String excerptText() {
        return text == null ? "" : text;
    }

    public String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("text:".getBytes(StandardCharsets.UTF_8));
            digest.update((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            if (images != null) {
                for (String image : images) {
                    digest.update("\nimage:".getBytes(StandardCharsets.UTF_8));
                    MessageDigest imageDigest = MessageDigest.getInstance("SHA-256");
                    digest.update(toHex(imageDigest.digest(image.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8));
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    public static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return String.join(" ", value.trim().split("\\s+")).trim();
    }

    public static List<String> normalizeImages(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim();
            if (!normalized.isEmpty()) {
                seen.add(normalized);
            }
        }
        return List.copyOf(seen);
    }
}
