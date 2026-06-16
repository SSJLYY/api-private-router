package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationModelFilterResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ContentModerationSupport {

    static final String CONFIG_KEY = "content_moderation_config";
    static final String LEGACY_CONFIG_KEY = "content_moderation_settings";
    static final String RISK_CONTROL_ENABLED_KEY = "risk_control_enabled";
    static final String DEFAULT_MODE = "pre_block";
    static final String DEFAULT_BASE_URL = "https://api.openai.com";
    static final String DEFAULT_MODEL = "omni-moderation-latest";
    static final String DEFAULT_BLOCK_MESSAGE = "内容审计命中风险规则，请调整输入后重试";
    static final int DEFAULT_TIMEOUT_MS = 3000;
    static final int MAX_TIMEOUT_MS = 30000;
    static final int DEFAULT_SAMPLE_RATE = 100;
    static final int DEFAULT_WORKER_COUNT = 4;
    static final int MAX_WORKER_COUNT = 32;
    static final int DEFAULT_QUEUE_SIZE = 32768;
    static final int MAX_QUEUE_SIZE = 100000;
    static final int DEFAULT_BLOCK_STATUS = 403;
    static final int DEFAULT_BAN_THRESHOLD = 10;
    static final int DEFAULT_VIOLATION_WINDOW_HOURS = 720;
    static final int DEFAULT_RETRY_COUNT = 2;
    static final int MAX_RETRY_COUNT = 5;
    static final int DEFAULT_HIT_RETENTION_DAYS = 180;
    static final int DEFAULT_NON_HIT_RETENTION_DAYS = 3;
    static final int MAX_HIT_RETENTION_DAYS = 3650;
    static final int MAX_NON_HIT_RETENTION_DAYS = 3;
    static final int API_KEY_FREEZE_THRESHOLD = 3;
    static final Duration API_KEY_FREEZE_DURATION = Duration.ofMinutes(1);
    static final long CLEANUP_INTERVAL_MS = 86_400_000L;
    static final long CLEANUP_INITIAL_DELAY_MS = 300_000L;
    static final int MAX_TEST_IMAGES = 4;
    static final List<String> THRESHOLD_ORDER = List.of(
            "harassment",
            "harassment/threatening",
            "hate",
            "hate/threatening",
            "illicit",
            "illicit/violent",
            "self-harm",
            "self-harm/intent",
            "self-harm/instructions",
            "sexual",
            "sexual/minors",
            "violence",
            "violence/graphic"
    );

    private ContentModerationSupport() {
    }

    static void normalizeConfigMap(Map<String, Object> settings) {
        settings.put("enabled", readBoolean(settings.get("enabled"), false));
        settings.put("mode", normalizeMode(settings.get("mode")));
        settings.put("base_url", normalizeBaseUrl(readString(settings.get("base_url"), DEFAULT_BASE_URL)));
        settings.put("model", normalizeModel(readString(settings.get("model"), DEFAULT_MODEL)));
        settings.put("api_keys", extractApiKeys(settings));
        settings.remove("api_key");
        settings.put("timeout_ms", normalizeTimeout(readInteger(settings.get("timeout_ms"))));
        settings.put("sample_rate", normalizeSampleRate(readInteger(settings.get("sample_rate"))));
        settings.put("all_groups", readBoolean(settings.get("all_groups"), true));
        settings.put("group_ids", normalizeLongIds(readLongList(settings.get("group_ids"))));
        settings.put("record_non_hits", readBoolean(settings.get("record_non_hits"), false));
        settings.put("worker_count", normalizeWorkerCount(readInteger(settings.get("worker_count"))));
        settings.put("queue_size", normalizeQueueSize(readInteger(settings.get("queue_size"))));
        settings.put("block_status", normalizeBlockStatus(readInteger(settings.get("block_status"))));
        settings.put("block_message", normalizeBlockMessage(readString(settings.get("block_message"), DEFAULT_BLOCK_MESSAGE)));
        settings.put("email_on_hit", readBoolean(settings.get("email_on_hit"), true));
        settings.put("auto_ban_enabled", readBoolean(settings.get("auto_ban_enabled"), true));
        settings.put("ban_threshold", normalizePositive(readInteger(settings.get("ban_threshold")), DEFAULT_BAN_THRESHOLD));
        settings.put("violation_window_hours", normalizePositive(
                readInteger(settings.get("violation_window_hours")),
                DEFAULT_VIOLATION_WINDOW_HOURS
        ));
        settings.put("retry_count", normalizeRetryCount(readInteger(settings.get("retry_count"))));
        settings.put("hit_retention_days", normalizeHitRetentionDays(readInteger(settings.get("hit_retention_days"))));
        settings.put("non_hit_retention_days", normalizeNonHitRetentionDays(readInteger(settings.get("non_hit_retention_days"))));
        settings.put("pre_hash_check_enabled", readBoolean(settings.get("pre_hash_check_enabled"), false));
        settings.put("blocked_keywords", normalizeBlockedKeywords(settings.get("blocked_keywords")));
        settings.put("keyword_blocking_mode", normalizeKeywordBlockingMode(settings.get("keyword_blocking_mode")));
        settings.put("model_filter", settings.get("model_filter"));
        settings.put("thresholds", normalizeThresholds(settings.get("thresholds")));
    }

    static Map<String, Double> normalizeThresholds(Object raw) {
        Map<String, Double> thresholds = defaultThresholds();
        if (raw instanceof Map<?, ?> values) {
            for (String category : THRESHOLD_ORDER) {
                Double value = toDouble(values.get(category));
                if (value != null) {
                    thresholds.put(category, clamp(value, 0D, 1D));
                }
            }
        }
        return thresholds;
    }

    static Map<String, Double> defaultThresholds() {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        thresholds.put("harassment", 0.98D);
        thresholds.put("harassment/threatening", 0.90D);
        thresholds.put("hate", 0.65D);
        thresholds.put("hate/threatening", 0.65D);
        thresholds.put("illicit", 0.95D);
        thresholds.put("illicit/violent", 0.95D);
        thresholds.put("self-harm", 0.65D);
        thresholds.put("self-harm/intent", 0.85D);
        thresholds.put("self-harm/instructions", 0.65D);
        thresholds.put("sexual", 0.65D);
        thresholds.put("sexual/minors", 0.65D);
        thresholds.put("violence", 0.95D);
        thresholds.put("violence/graphic", 0.95D);
        return thresholds;
    }

    static List<String> extractApiKeys(Map<String, Object> settings) {
        List<String> apiKeys = new ArrayList<>(readStringList(settings.get("api_keys")));
        String legacyApiKey = trimToNull(readString(settings.get("api_key"), null));
        if (legacyApiKey != null) {
            apiKeys.add(legacyApiKey);
        }
        return normalizeApiKeys(apiKeys);
    }

    static boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }

    static Integer readInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String readString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static List<String> readStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(ContentModerationSupport::trimToNull)
                    .filter(value -> value != null)
                    .toList();
        }
        return List.of();
    }

    static List<Long> readLongList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> {
                    if (value instanceof Number number) {
                        return number.longValue();
                    }
                    return toLong(value);
                })
                .filter(value -> value != null && value > 0)
                .toList();
    }

    static List<Long> normalizeLongIds(List<Long> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    static List<String> normalizeApiKeys(List<String> apiKeys) {
        if (apiKeys == null) {
            return List.of();
        }
        return apiKeys.stream()
                .map(ContentModerationSupport::trimToNull)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    static List<String> normalizeImages(List<String> images) {
        if (images == null) {
            return List.of();
        }
        return images.stream()
                .map(ContentModerationSupport::trimToNull)
                .filter(value -> value != null)
                .toList();
    }

    static int normalizeTimeout(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.min(value, MAX_TIMEOUT_MS);
    }

    static int normalizeSampleRate(Integer value) {
        if (value == null) {
            return DEFAULT_SAMPLE_RATE;
        }
        return Math.max(0, Math.min(value, 100));
    }

    static int normalizeWorkerCount(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_WORKER_COUNT;
        }
        return Math.min(value, MAX_WORKER_COUNT);
    }

    static int normalizeQueueSize(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_QUEUE_SIZE;
        }
        return Math.min(value, MAX_QUEUE_SIZE);
    }

    static int normalizeBlockStatus(Integer value) {
        return value == null || value <= 0 ? DEFAULT_BLOCK_STATUS : value;
    }

    static int normalizePositive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    static int normalizeRetryCount(Integer value) {
        if (value == null || value < 0) {
            return DEFAULT_RETRY_COUNT;
        }
        return Math.min(value, MAX_RETRY_COUNT);
    }

    static int normalizeHitRetentionDays(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_HIT_RETENTION_DAYS;
        }
        return Math.min(value, MAX_HIT_RETENTION_DAYS);
    }

    static int normalizeNonHitRetentionDays(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_NON_HIT_RETENTION_DAYS;
        }
        return Math.min(value, MAX_NON_HIT_RETENTION_DAYS);
    }

    static String normalizeMode(Object rawMode) {
        String mode = trimToNull(String.valueOf(rawMode == null ? DEFAULT_MODE : rawMode));
        if ("off".equals(mode) || "observe".equals(mode) || "pre_block".equals(mode)) {
            return mode;
        }
        return DEFAULT_MODE;
    }

    static String normalizeBaseUrl(String baseUrl) {
        String normalized = trimToNull(baseUrl);
        if (normalized == null) {
            normalized = DEFAULT_BASE_URL;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String normalizeModel(String model) {
        String normalized = trimToNull(model);
        return normalized == null ? DEFAULT_MODEL : normalized;
    }

    static String normalizeBlockMessage(String blockMessage) {
        String normalized = trimToNull(blockMessage);
        return normalized == null ? DEFAULT_BLOCK_MESSAGE : normalized;
    }

    static String normalizeLogTime(String value, boolean endOfDay, String label) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Instant.parse(normalized).toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant().toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(normalized);
            return dateTime.toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate date = LocalDate.parse(normalized);
            if (endOfDay) {
                return date.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC).toString();
            }
            return date.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    static String normalizeInputHash(String inputHash) {
        String normalized = trimToNull(inputHash);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase();
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("input_hash must be a 64-char sha256 hex string");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            boolean hexChar = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
            if (!hexChar) {
                throw new IllegalArgumentException("input_hash must be a 64-char sha256 hex string");
            }
        }
        return normalized;
    }

    static List<String> normalizeBlockedKeywords(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(item -> item instanceof String s ? s.trim() : "")
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    static String normalizeKeywordBlockingMode(Object raw) {
        if (raw instanceof String s) {
            String mode = s.trim().toLowerCase();
            if ("block_request".equals(mode) || "append_warning".equals(mode) || "off".equals(mode)) {
                return mode;
            }
        }
        return "block_request";
    }

    static ContentModerationModelFilterResponse normalizeModelFilter(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            String mode = map.get("mode") instanceof String s ? s : "all";
            List<String> models = map.get("models") instanceof List<?> list
                    ? list.stream().filter(item -> item instanceof String).map(item -> (String) item).limit(1000).toList()
                    : List.of();
            return new ContentModerationModelFilterResponse(mode, models);
        }
        return new ContentModerationModelFilterResponse("all", List.of());
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    static String maskApiKey(String apiKey) {
        String normalized = trimToNull(apiKey);
        if (normalized == null) {
            return "";
        }
        if (normalized.length() <= 4) {
            return "****";
        }
        return "********" + normalized.substring(normalized.length() - 4);
    }

    static String hashApiKey(String apiKey) {
        String normalized = trimToNull(apiKey);
        if (normalized == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    static int elapsedMillis(long startedAt) {
        return (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    static String trimErrorMessage(Exception ex) {
        String message = trimToNull(ex.getMessage());
        return message == null ? ex.getClass().getSimpleName() : truncate(message, 180);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

record ContentModerationLoadedConfig(
        Map<String, Object> rawSettings,
        org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationConfigResponse response,
        List<String> apiKeys,
        Map<String, Double> thresholds
) {
}

record ContentModerationBanDecision(
        int violationCount,
        boolean autoBanned,
        boolean banApplied,
        String status
) {
}
