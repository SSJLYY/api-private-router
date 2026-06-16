package org.apiprivaterouter.javabackend.riskcontrol.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationApiKeyStatus;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationConfigResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationModelFilterResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationHashRepository;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationRepository;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.extractor.ContentModerationInputExtractor;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationCheckInput;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationConfigSnapshot;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationDecision;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationInput;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationLogRecord;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationRuntimeStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service("gatewayContentModerationRuntimeService")
public class ContentModerationRuntimeService {

    public static final String CONFIG_KEY = "content_moderation_config";
    public static final String LEGACY_CONFIG_KEY = "content_moderation_settings";
    public static final String RISK_CONTROL_ENABLED_KEY = "risk_control_enabled";
    public static final String MODE_OFF = "off";
    public static final String MODE_OBSERVE = "observe";
    public static final String MODE_PRE_BLOCK = "pre_block";
    public static final String ACTION_ALLOW = "allow";
    public static final String ACTION_BLOCK = "block";
    public static final String ACTION_HASH_BLOCK = "hash_block";
    public static final String ACTION_ERROR = "error";

    private static final String DEFAULT_MODE = MODE_PRE_BLOCK;
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_MODEL = "omni-moderation-latest";
    private static final String DEFAULT_BLOCK_MESSAGE = "内容审核命中风险规则，请调整输入后重试";
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final int DEFAULT_SAMPLE_RATE = 100;
    private static final int DEFAULT_WORKER_COUNT = 4;
    private static final int MAX_WORKER_COUNT = 32;
    private static final int DEFAULT_QUEUE_SIZE = 32768;
    private static final int MAX_QUEUE_SIZE = 100000;
    private static final int DEFAULT_BLOCK_STATUS = 403;
    private static final int DEFAULT_BAN_THRESHOLD = 10;
    private static final int DEFAULT_VIOLATION_WINDOW_HOURS = 720;
    private static final int DEFAULT_RETRY_COUNT = 2;
    private static final int MAX_RETRY_COUNT = 5;
    private static final int DEFAULT_HIT_RETENTION_DAYS = 180;
    private static final int DEFAULT_NON_HIT_RETENTION_DAYS = 3;
    private static final int MAX_HIT_RETENTION_DAYS = 3650;
    private static final int MAX_NON_HIT_RETENTION_DAYS = 3;
    private static final int API_KEY_FREEZE_THRESHOLD = 3;
    private static final Duration API_KEY_FREEZE_DURATION = Duration.ofMinutes(1);
    private static final List<String> THRESHOLD_ORDER = List.of(
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

    private final AdminSettingsRepository settingsRepository;
    private final ContentModerationRepository moderationRepository;
    private final ContentModerationHashRepository hashRepository;
    private final JsonHelper jsonHelper;
    private final ObjectMapper objectMapper;
    private final ContentModerationInputExtractor inputExtractor;
    private final HttpClient httpClient;
    private final Map<String, ApiKeyRuntimeState> apiKeyHealth = new ConcurrentHashMap<>();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong apiKeyCursor = new AtomicLong();

    public ContentModerationRuntimeService(
            AdminSettingsRepository settingsRepository,
            ContentModerationRepository moderationRepository,
            ContentModerationHashRepository hashRepository,
            JsonHelper jsonHelper,
            ObjectMapper objectMapper,
            ContentModerationInputExtractor inputExtractor
    ) {
        this.settingsRepository = settingsRepository;
        this.moderationRepository = moderationRepository;
        this.hashRepository = hashRepository;
        this.jsonHelper = jsonHelper;
        this.objectMapper = objectMapper;
        this.inputExtractor = inputExtractor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ContentModerationDecision check(ContentModerationCheckInput input) {
        ContentModerationDecision allow = ContentModerationDecision.allow();
        if (input == null || !isRiskControlEnabled()) {
            return allow;
        }

        ContentModerationConfigSnapshot config = loadConfigSnapshot();
        if (!config.enabled() || MODE_OFF.equals(config.mode()) || !config.includesGroup(input.groupId())) {
            return allow;
        }

        if (config.modelFilter() != null && input.model() != null && !config.modelFilter().appliesToModel(input.model())) {
            return allow;
        }

        ContentModerationInput content = inputExtractor.extract(input.protocol(), input.body());
        if (content.isEmpty()) {
            return allow;
        }

        String hash = content.hash();
        if (config.preHashCheckEnabled() && hashRepository.has(hash)) {
            return new ContentModerationDecision(
                    false,
                    true,
                    true,
                    appendHashToMessage(config.blockMessage(), hash),
                    config.blockStatus(),
                    hash,
                    "",
                    0D,
                    Map.of(),
                    ACTION_HASH_BLOCK
            );
        }

        if (config.isKeywordBlockingEnabled() && content.text() != null && !content.text().isEmpty()) {
            String normalizedContent = content.text().toLowerCase();
            for (String keyword : config.blockedKeywords()) {
                if (keyword != null && !keyword.isEmpty() && normalizedContent.contains(keyword.toLowerCase())) {
                    return new ContentModerationDecision(
                            false,
                            true,
                            true,
                            appendKeywordToMessage(config.blockMessage(), keyword),
                            config.blockStatus(),
                            hash,
                            keyword,
                            1.0,
                            Map.of("keyword", 1.0),
                            "keyword_block"
                    );
                }
            }
            if ("keyword_only".equals(config.keywordBlockingMode())) {
                return allow;
            }
        }

        if (!config.shouldSample(hash) || config.apiKeys().isEmpty() || !config.shouldCallModerationApi()) {
            return allow;
        }

        ModerationOutcome outcome = callModeration(config, content);
        if (outcome.errorMessage() != null) {
            errors.incrementAndGet();
            if (config.recordNonHits()) {
                moderationRepository.createLog(buildLog(
                        input,
                        config,
                        ACTION_ERROR,
                        false,
                        "",
                        0D,
                        Map.of(),
                        content.excerptText(),
                        outcome.latencyMs(),
                        outcome.errorMessage(),
                        0,
                        false,
                        false,
                        "",
                        null
                ));
            }
            return allow;
        }

        processed.incrementAndGet();
        boolean flagged = outcome.flagged();
        String action = flagged && MODE_PRE_BLOCK.equals(config.mode()) ? ACTION_BLOCK : ACTION_ALLOW;
        FlaggedSideEffects sideEffects = flagged
                ? applyFlaggedSideEffects(input, config)
                : FlaggedSideEffects.none();

        if (flagged || config.recordNonHits()) {
            ContentModerationLogRecord log = buildLog(
                    input,
                    config,
                    action,
                    flagged,
                    outcome.highestCategory(),
                    outcome.highestScore(),
                    outcome.categoryScores(),
                    content.excerptText(),
                    outcome.latencyMs(),
                    "",
                    sideEffects.violationCount(),
                    sideEffects.autoBanned(),
                    false,
                    sideEffects.userStatus(),
                    null
            );
            if (flagged) {
                hashRepository.record(hash);
            }
            moderationRepository.createLog(log);
        }

        if (flagged && MODE_PRE_BLOCK.equals(config.mode())) {
            return new ContentModerationDecision(
                    false,
                    true,
                    true,
                    config.blockMessage(),
                    config.blockStatus(),
                    hash,
                    outcome.highestCategory(),
                    outcome.highestScore(),
                    outcome.categoryScores(),
                    ACTION_BLOCK
            );
        }

        return new ContentModerationDecision(
                true,
                false,
                flagged,
                "",
                0,
                hash,
                outcome.highestCategory(),
                outcome.highestScore(),
                outcome.categoryScores(),
                action
        );
    }

    public ContentModerationRuntimeStatus getRuntimeStatus() {
        ContentModerationConfigSnapshot config = loadConfigSnapshot();
        return new ContentModerationRuntimeStatus(
                config.enabled(),
                isRiskControlEnabled(),
                config.mode(),
                config.workerCount(),
                MAX_WORKER_COUNT,
                0,
                config.workerCount(),
                config.queueSize(),
                0,
                0D,
                processed.get(),
                0,
                processed.get(),
                errors.get(),
                buildConfiguredApiKeyStatuses(config.apiKeys()),
                hashRepository.count(),
                null,
                0,
                0
        );
    }

    public ContentModerationConfigSnapshot loadConfigSnapshot() {
        Map<String, Object> settings = new LinkedHashMap<>(loadSettings());
        normalizeConfigMap(settings);
        List<String> apiKeys = extractApiKeys(settings);
        return new ContentModerationConfigSnapshot(
                readBoolean(settings.get("enabled"), false),
                normalizeMode(settings.get("mode")),
                normalizeBaseUrl(readString(settings.get("base_url"), DEFAULT_BASE_URL)),
                normalizeModel(readString(settings.get("model"), DEFAULT_MODEL)),
                apiKeys,
                buildConfiguredApiKeyStatuses(apiKeys),
                normalizeTimeout(readInteger(settings.get("timeout_ms"))),
                normalizeSampleRate(readInteger(settings.get("sample_rate"))),
                readBoolean(settings.get("all_groups"), true),
                normalizeLongIds(readLongList(settings.get("group_ids"))),
                readBoolean(settings.get("record_non_hits"), false),
                normalizeWorkerCount(readInteger(settings.get("worker_count"))),
                normalizeQueueSize(readInteger(settings.get("queue_size"))),
                normalizeBlockStatus(readInteger(settings.get("block_status"))),
                normalizeBlockMessage(readString(settings.get("block_message"), DEFAULT_BLOCK_MESSAGE)),
                readBoolean(settings.get("email_on_hit"), true),
                readBoolean(settings.get("auto_ban_enabled"), true),
                normalizePositive(readInteger(settings.get("ban_threshold")), DEFAULT_BAN_THRESHOLD),
                normalizePositive(readInteger(settings.get("violation_window_hours")), DEFAULT_VIOLATION_WINDOW_HOURS),
                normalizeRetryCount(readInteger(settings.get("retry_count"))),
                normalizeHitRetentionDays(readInteger(settings.get("hit_retention_days"))),
                normalizeNonHitRetentionDays(readInteger(settings.get("non_hit_retention_days"))),
                readBoolean(settings.get("pre_hash_check_enabled"), false),
                normalizeThresholds(settings.get("thresholds")),
                normalizeBlockedKeywords(settings.get("blocked_keywords")),
                normalizeKeywordBlockingMode(readString(settings.get("keyword_blocking_mode"), null)),
                normalizeModelFilter(settings.get("model_filter"))
        );
    }

    public ContentModerationConfigResponse toConfigResponse(ContentModerationConfigSnapshot snapshot) {
        List<String> masks = snapshot.apiKeys().stream().map(this::maskApiKey).toList();
        return new ContentModerationConfigResponse(
                snapshot.enabled(),
                snapshot.mode(),
                snapshot.baseUrl(),
                snapshot.model(),
                !snapshot.apiKeys().isEmpty(),
                masks.isEmpty() ? "" : masks.get(0),
                snapshot.apiKeys().size(),
                masks,
                snapshot.apiKeyStatuses(),
                snapshot.timeoutMs(),
                snapshot.sampleRate(),
                snapshot.allGroups(),
                snapshot.groupIds(),
                snapshot.recordNonHits(),
                snapshot.workerCount(),
                snapshot.queueSize(),
                snapshot.blockStatus(),
                snapshot.blockMessage(),
                snapshot.emailOnHit(),
                snapshot.autoBanEnabled(),
                snapshot.banThreshold(),
                snapshot.violationWindowHours(),
                snapshot.retryCount(),
                snapshot.hitRetentionDays(),
                snapshot.nonHitRetentionDays(),
                snapshot.preHashCheckEnabled(),
                snapshot.blockedKeywords(),
                snapshot.keywordBlockingMode(),
                snapshot.thresholds(),
                snapshot.modelFilter() != null
                        ? new ContentModerationModelFilterResponse(snapshot.modelFilter().mode(), snapshot.modelFilter().models())
                        : null
        );
    }

    public boolean isRiskControlEnabled() {
        return readBoolean(settingsRepository.getSettingValue(RISK_CONTROL_ENABLED_KEY), false);
    }

    private ModerationOutcome callModeration(ContentModerationConfigSnapshot config, ContentModerationInput content) {
        int attempts = Math.max(1, Math.min(config.retryCount() + 1, MAX_RETRY_COUNT + 1));
        ModerationOutcome lastOutcome = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String apiKey = nextUsableApiKey(config);
            if (apiKey == null) {
                return new ModerationOutcome(false, "", 0D, Map.of(), 0, "no moderation api key available");
            }
            ModerationOutcome outcome = callModerationOnce(config, content, apiKey);
            if (outcome.errorMessage() == null) {
                return outcome;
            }
            lastOutcome = outcome;
            if (attempt == attempts - 1) {
                break;
            }
            sleepBeforeRetry(attempt);
        }
        return lastOutcome == null
                ? new ModerationOutcome(false, "", 0D, Map.of(), 0, "moderation request failed")
                : lastOutcome;
    }

    private ModerationOutcome callModerationOnce(ContentModerationConfigSnapshot config, ContentModerationInput content, String apiKey) {
        long startedAt = System.nanoTime();
        int httpStatus = 0;
        try {
            Map<String, Object> payload = Map.of(
                    "model", config.model(),
                    "input", content.moderationInput()
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/moderations"))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            httpStatus = response.statusCode();
            int latencyMs = elapsedMillis(startedAt);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = extractUpstreamError(response.body(), response.statusCode());
                markApiKeyFailure(apiKey, message, latencyMs, httpStatus);
                return new ModerationOutcome(false, "", 0D, Map.of(), latencyMs, message);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            JsonNode first = results.isArray() && !results.isEmpty() ? results.get(0) : objectMapper.createObjectNode();
            JsonNode scoresNode = first.path("category_scores");
            Map<String, Double> scores = new LinkedHashMap<>();
            double highestScore = 0D;
            String highestCategory = "";
            if (scoresNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = scoresNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    double score = field.getValue().asDouble(0D);
                    scores.put(field.getKey(), score);
                    if (highestCategory.isEmpty() || score > highestScore) {
                        highestCategory = field.getKey();
                        highestScore = score;
                    }
                }
            }

            boolean flagged = first.path("flagged").asBoolean(false);
            for (Map.Entry<String, Double> entry : scores.entrySet()) {
                if (entry.getValue() >= config.thresholds().getOrDefault(entry.getKey(), 1D)) {
                    flagged = true;
                }
            }

            markApiKeySuccess(apiKey, latencyMs, httpStatus);
            return new ModerationOutcome(flagged, highestCategory, highestScore, scores, latencyMs, null);
        } catch (Exception ex) {
            int latencyMs = elapsedMillis(startedAt);
            String message = trimErrorMessage(ex);
            markApiKeyFailure(apiKey, message, latencyMs, httpStatus);
            return new ModerationOutcome(false, "", 0D, Map.of(), latencyMs, message);
        }
    }

    private ContentModerationLogRecord buildLog(
            ContentModerationCheckInput input,
            ContentModerationConfigSnapshot config,
            String action,
            boolean flagged,
            String highestCategory,
            double highestScore,
            Map<String, Double> categoryScores,
            String inputExcerpt,
            Integer latencyMs,
            String error,
            int violationCount,
            boolean autoBanned,
            boolean emailSent,
            String userStatus,
            Integer queueDelayMs
    ) {
        return new ContentModerationLogRecord(
                0L,
                input.requestId(),
                input.userId() <= 0 ? null : input.userId(),
                defaultString(input.userEmail()),
                input.apiKeyId() <= 0 ? null : input.apiKeyId(),
                defaultString(input.apiKeyName()),
                input.groupId(),
                defaultString(input.groupName()),
                defaultString(input.endpoint()),
                defaultString(input.provider()),
                defaultString(input.model()),
                config.mode(),
                action,
                flagged,
                defaultString(highestCategory),
                highestScore,
                categoryScores == null ? Map.of() : categoryScores,
                config.thresholds(),
                truncate(inputExcerpt, 240),
                latencyMs,
                defaultString(error),
                Math.max(0, violationCount),
                autoBanned,
                emailSent,
                defaultString(userStatus),
                queueDelayMs,
                null
        );
    }

    private FlaggedSideEffects applyFlaggedSideEffects(ContentModerationCheckInput input, ContentModerationConfigSnapshot config) {
        if (input.userId() <= 0) {
            return FlaggedSideEffects.none();
        }

        String userStatus = moderationRepository.findUserStatus(input.userId()).orElse("");
        int count = moderationRepository.countFlaggedByUserSince(
                input.userId(),
                Instant.now().minus(Duration.ofHours(config.violationWindowHours()))
        ) + 1;

        boolean autoBanned = false;
        if (config.autoBanEnabled()
                && config.banThreshold() > 0
                && count >= config.banThreshold()) {
            autoBanned = true;
            if (!"disabled".equalsIgnoreCase(userStatus)) {
                moderationRepository.disableUser(input.userId());
            }
            userStatus = "disabled";
        }

        return new FlaggedSideEffects(count, autoBanned, userStatus);
    }

    private Map<String, Object> loadSettings() {
        String raw = settingsRepository.getSettingValue(CONFIG_KEY);
        if (raw == null || raw.isBlank()) {
            raw = settingsRepository.getSettingValue(LEGACY_CONFIG_KEY);
        }
        return raw == null ? Map.of() : jsonHelper.readObjectMap(raw);
    }

    private void normalizeConfigMap(Map<String, Object> settings) {
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
        settings.put("violation_window_hours", normalizePositive(readInteger(settings.get("violation_window_hours")), DEFAULT_VIOLATION_WINDOW_HOURS));
        settings.put("retry_count", normalizeRetryCount(readInteger(settings.get("retry_count"))));
        settings.put("hit_retention_days", normalizeHitRetentionDays(readInteger(settings.get("hit_retention_days"))));
        settings.put("non_hit_retention_days", normalizeNonHitRetentionDays(readInteger(settings.get("non_hit_retention_days"))));
        settings.put("pre_hash_check_enabled", readBoolean(settings.get("pre_hash_check_enabled"), false));
        settings.put("thresholds", normalizeThresholds(settings.get("thresholds")));
    }

    private List<String> extractApiKeys(Map<String, Object> settings) {
        List<String> apiKeys = new ArrayList<>(readStringList(settings.get("api_keys")));
        String legacy = trimToNull(readString(settings.get("api_key"), null));
        if (legacy != null) {
            apiKeys.add(legacy);
        }
        return normalizeApiKeys(apiKeys);
    }

    private List<ContentModerationApiKeyStatus> buildConfiguredApiKeyStatuses(List<String> apiKeys) {
        List<ContentModerationApiKeyStatus> items = new ArrayList<>(apiKeys.size());
        for (int index = 0; index < apiKeys.size(); index++) {
            items.add(buildApiKeyStatus(index, apiKeys.get(index), true));
        }
        return items;
    }

    private ContentModerationApiKeyStatus buildApiKeyStatus(int index, String apiKey, boolean configured) {
        String hash = hashApiKey(apiKey);
        String masked = maskApiKey(apiKey);
        if (hash == null) {
            return new ContentModerationApiKeyStatus(index, "", masked, "unknown", 0, 0, "", null, null, 0, 0, false, configured);
        }
        ApiKeyRuntimeState state = apiKeyHealth.get(hash);
        if (state == null) {
            return new ContentModerationApiKeyStatus(index, hash, masked, "unknown", 0, 0, "", null, null, 0, 0, false, configured);
        }
        synchronized (state) {
            String status = "unknown";
            Instant now = Instant.now();
            if (state.frozenUntil != null && state.frozenUntil.isAfter(now)) {
                status = "frozen";
            } else if (state.lastError != null && !state.lastError.isBlank()) {
                status = "error";
            } else if (state.successCount > 0 || state.lastTested) {
                status = "ok";
            }
            return new ContentModerationApiKeyStatus(
                    index,
                    hash,
                    masked,
                    status,
                    state.failureCount,
                    state.successCount,
                    defaultString(state.lastError),
                    state.lastCheckedAt == null ? null : state.lastCheckedAt.toString(),
                    state.frozenUntil == null ? null : state.frozenUntil.toString(),
                    state.lastLatencyMs,
                    state.lastHttpStatus,
                    state.lastTested,
                    configured
            );
        }
    }

    private void markApiKeySuccess(String apiKey, int latencyMs, int httpStatus) {
        String hash = hashApiKey(apiKey);
        if (hash == null) {
            return;
        }
        ApiKeyRuntimeState state = apiKeyHealth.computeIfAbsent(hash, unused -> new ApiKeyRuntimeState(maskApiKey(apiKey)));
        synchronized (state) {
            state.masked = maskApiKey(apiKey);
            state.failureCount = 0;
            state.successCount++;
            state.lastError = "";
            state.lastCheckedAt = Instant.now();
            state.frozenUntil = null;
            state.lastLatencyMs = latencyMs;
            state.lastHttpStatus = httpStatus;
            state.lastTested = true;
        }
    }

    private void markApiKeyFailure(String apiKey, String error, int latencyMs, int httpStatus) {
        String hash = hashApiKey(apiKey);
        if (hash == null) {
            return;
        }
        ApiKeyRuntimeState state = apiKeyHealth.computeIfAbsent(hash, unused -> new ApiKeyRuntimeState(maskApiKey(apiKey)));
        synchronized (state) {
            state.masked = maskApiKey(apiKey);
            state.failureCount++;
            state.lastError = truncate(error, 180);
            state.lastCheckedAt = Instant.now();
            state.lastLatencyMs = latencyMs;
            state.lastHttpStatus = httpStatus;
            state.lastTested = true;
            if (state.failureCount >= API_KEY_FREEZE_THRESHOLD) {
                state.frozenUntil = Instant.now().plus(API_KEY_FREEZE_DURATION);
            }
        }
    }

    private String nextUsableApiKey(ContentModerationConfigSnapshot config) {
        List<String> apiKeys = config.apiKeys();
        if (apiKeys == null || apiKeys.isEmpty()) {
            return null;
        }
        Instant now = Instant.now();
        for (int attempt = 0; attempt < apiKeys.size(); attempt++) {
            int index = Math.floorMod((int) apiKeyCursor.getAndIncrement(), apiKeys.size());
            String candidate = apiKeys.get(index);
            if (!isApiKeyFrozen(candidate, now)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isApiKeyFrozen(String apiKey, Instant now) {
        String hash = hashApiKey(apiKey);
        if (hash == null) {
            return false;
        }
        ApiKeyRuntimeState state = apiKeyHealth.get(hash);
        return state != null && state.frozenUntil != null && state.frozenUntil.isAfter(now);
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * (attempt + 1L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractUpstreamError(String body, int httpStatus) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = trimToNull(root.path("error").path("message").asText(null));
            if (message == null) {
                message = trimToNull(root.path("message").asText(null));
            }
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
        }
        String compactBody = trimToNull(body == null ? null : body.replaceAll("\\s+", " "));
        if (compactBody != null) {
            return truncate(compactBody, 180);
        }
        return "HTTP " + httpStatus;
    }

    private String appendHashToMessage(String message, String hash) {
        if (message == null || message.isBlank()) {
            return "content moderation blocked this request (hash: " + hash + ")";
        }
        return message + " (hash: " + hash + ")";
    }

    private String appendKeywordToMessage(String message, String keyword) {
        if (message == null || message.isBlank()) {
            return "content moderation blocked this request (keyword matched)";
        }
        return message + " (keyword matched)";
    }

    private List<String> normalizeApiKeys(List<String> apiKeys) {
        if (apiKeys == null) {
            return List.of();
        }
        return apiKeys.stream()
                .map(this::trimToNull)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private List<Long> normalizeLongIds(List<Long> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private Map<String, Double> normalizeThresholds(Object raw) {
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

    private Map<String, Double> defaultThresholds() {
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

    private boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }

    private Integer readInteger(Object value) {
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

    private String readString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .map(this::trimToNull)
                .filter(value -> value != null)
                .toList();
    }

    private List<Long> readLongList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> {
                    if (value instanceof Number number) {
                        return number.longValue();
                    }
                    try {
                        return Long.parseLong(String.valueOf(value));
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                })
                .filter(value -> value != null && value > 0)
                .toList();
    }

    private int normalizeTimeout(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.min(value, MAX_TIMEOUT_MS);
    }

    private int normalizeSampleRate(Integer value) {
        if (value == null) {
            return DEFAULT_SAMPLE_RATE;
        }
        return Math.max(0, Math.min(value, 100));
    }

    private int normalizeWorkerCount(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_WORKER_COUNT;
        }
        return Math.min(value, MAX_WORKER_COUNT);
    }

    private int normalizeQueueSize(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_QUEUE_SIZE;
        }
        return Math.min(value, MAX_QUEUE_SIZE);
    }

    private int normalizeBlockStatus(Integer value) {
        return value == null || value <= 0 ? DEFAULT_BLOCK_STATUS : value;
    }

    private int normalizePositive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int normalizeRetryCount(Integer value) {
        if (value == null || value < 0) {
            return DEFAULT_RETRY_COUNT;
        }
        return Math.min(value, MAX_RETRY_COUNT);
    }

    private int normalizeHitRetentionDays(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_HIT_RETENTION_DAYS;
        }
        return Math.min(value, MAX_HIT_RETENTION_DAYS);
    }

    private int normalizeNonHitRetentionDays(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_NON_HIT_RETENTION_DAYS;
        }
        return Math.min(value, MAX_NON_HIT_RETENTION_DAYS);
    }

    private String normalizeMode(Object rawMode) {
        String mode = trimToNull(String.valueOf(rawMode == null ? DEFAULT_MODE : rawMode));
        if (MODE_OFF.equals(mode) || MODE_OBSERVE.equals(mode) || MODE_PRE_BLOCK.equals(mode)) {
            return mode;
        }
        return DEFAULT_MODE;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = trimToNull(baseUrl);
        if (normalized == null) {
            normalized = DEFAULT_BASE_URL;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeModel(String model) {
        String normalized = trimToNull(model);
        return normalized == null ? DEFAULT_MODEL : normalized;
    }

    private String normalizeBlockMessage(String blockMessage) {
        String normalized = trimToNull(blockMessage);
        return normalized == null ? DEFAULT_BLOCK_MESSAGE : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Double toDouble(Object value) {
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private String maskApiKey(String apiKey) {
        String normalized = trimToNull(apiKey);
        if (normalized == null) {
            return "";
        }
        if (normalized.length() <= 4) {
            return "****";
        }
        return "********" + normalized.substring(normalized.length() - 4);
    }

    private String hashApiKey(String apiKey) {
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

    private int elapsedMillis(long startedAt) {
        return (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String trimErrorMessage(Exception ex) {
        String message = trimToNull(ex.getMessage());
        return message == null ? ex.getClass().getSimpleName() : truncate(message, 180);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record ModerationOutcome(
            boolean flagged,
            String highestCategory,
            double highestScore,
            Map<String, Double> categoryScores,
            int latencyMs,
            String errorMessage
    ) {
    }

    private record FlaggedSideEffects(
            int violationCount,
            boolean autoBanned,
            String userStatus
    ) {
        private static FlaggedSideEffects none() {
            return new FlaggedSideEffects(0, false, "");
        }
    }

    private static final class ApiKeyRuntimeState {
        private String masked;
        private int failureCount;
        private long successCount;
        private String lastError;
        private Instant lastCheckedAt;
        private Instant frozenUntil;
        private int lastLatencyMs;
        private int lastHttpStatus;
        private boolean lastTested;

        private ApiKeyRuntimeState(String masked) {
            this.masked = masked;
        }
    }

    private List<String> normalizeBlockedKeywords(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .filter(s -> !s.isBlank())
                    .limit(10000)
                    .toList();
        }
        return List.of();
    }

    private String normalizeKeywordBlockingMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        return switch (mode.toLowerCase()) {
            case "keyword_only", "keyword_and_api", "api_only" -> mode.toLowerCase();
            default -> null;
        };
    }

    private ContentModerationConfigSnapshot.ContentModerationModelFilter normalizeModelFilter(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            String mode = map.get("mode") instanceof String s ? s : "all";
            List<String> models = map.get("models") instanceof List<?> list
                    ? list.stream().filter(item -> item instanceof String).map(item -> (String) item).limit(1000).toList()
                    : List.of();
            return new ContentModerationConfigSnapshot.ContentModerationModelFilter(mode, models);
        }
        return ContentModerationConfigSnapshot.ContentModerationModelFilter.all();
    }
}
