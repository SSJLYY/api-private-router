package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationConfigResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationModelFilterResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.UpdateContentModerationConfigRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationHashRepository;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContentModerationConfigService {

    private final AdminSettingsRepository settingsRepository;
    private final JsonHelper jsonHelper;
    private final ContentModerationApiKeyHealthTracker apiKeyHealthTracker;
    private final ContentModerationRuntimeService runtimeService;
    @SuppressWarnings("unused")
    private final ContentModerationHashRepository hashRepository;

    public ContentModerationConfigService(
            AdminSettingsRepository settingsRepository,
            JsonHelper jsonHelper,
            ContentModerationApiKeyHealthTracker apiKeyHealthTracker,
            ContentModerationRuntimeService runtimeService,
            ContentModerationHashRepository hashRepository
    ) {
        this.settingsRepository = settingsRepository;
        this.jsonHelper = jsonHelper;
        this.apiKeyHealthTracker = apiKeyHealthTracker;
        this.runtimeService = runtimeService;
        this.hashRepository = hashRepository;
    }

    public ContentModerationLoadedConfig loadConfig() {
        Map<String, Object> settings = new LinkedHashMap<>(loadSettings());
        ContentModerationSupport.normalizeConfigMap(settings);
        List<String> apiKeys = ContentModerationSupport.extractApiKeys(settings);
        Map<String, Double> thresholds = ContentModerationSupport.normalizeThresholds(settings.get("thresholds"));
        List<String> apiKeyMasks = apiKeys.stream().map(ContentModerationSupport::maskApiKey).toList();
        String apiKeyMasked = apiKeyMasks.isEmpty() ? "" : apiKeyMasks.get(0);
        ContentModerationConfigResponse response = new ContentModerationConfigResponse(
                ContentModerationSupport.readBoolean(settings.get("enabled"), false),
                ContentModerationSupport.normalizeMode(settings.get("mode")),
                ContentModerationSupport.normalizeBaseUrl(ContentModerationSupport.readString(settings.get("base_url"), ContentModerationSupport.DEFAULT_BASE_URL)),
                ContentModerationSupport.normalizeModel(ContentModerationSupport.readString(settings.get("model"), ContentModerationSupport.DEFAULT_MODEL)),
                !apiKeys.isEmpty(),
                apiKeyMasked,
                apiKeys.size(),
                apiKeyMasks,
                apiKeyHealthTracker.buildStatuses(apiKeys, true),
                ContentModerationSupport.normalizeTimeout(ContentModerationSupport.readInteger(settings.get("timeout_ms"))),
                ContentModerationSupport.normalizeSampleRate(ContentModerationSupport.readInteger(settings.get("sample_rate"))),
                ContentModerationSupport.readBoolean(settings.get("all_groups"), true),
                ContentModerationSupport.normalizeLongIds(ContentModerationSupport.readLongList(settings.get("group_ids"))),
                ContentModerationSupport.readBoolean(settings.get("record_non_hits"), false),
                ContentModerationSupport.normalizeWorkerCount(ContentModerationSupport.readInteger(settings.get("worker_count"))),
                ContentModerationSupport.normalizeQueueSize(ContentModerationSupport.readInteger(settings.get("queue_size"))),
                ContentModerationSupport.normalizeBlockStatus(ContentModerationSupport.readInteger(settings.get("block_status"))),
                ContentModerationSupport.normalizeBlockMessage(ContentModerationSupport.readString(settings.get("block_message"), ContentModerationSupport.DEFAULT_BLOCK_MESSAGE)),
                ContentModerationSupport.readBoolean(settings.get("email_on_hit"), true),
                ContentModerationSupport.readBoolean(settings.get("auto_ban_enabled"), true),
                ContentModerationSupport.normalizePositive(ContentModerationSupport.readInteger(settings.get("ban_threshold")), ContentModerationSupport.DEFAULT_BAN_THRESHOLD),
                ContentModerationSupport.normalizePositive(
                        ContentModerationSupport.readInteger(settings.get("violation_window_hours")),
                        ContentModerationSupport.DEFAULT_VIOLATION_WINDOW_HOURS
                ),
                ContentModerationSupport.normalizeRetryCount(ContentModerationSupport.readInteger(settings.get("retry_count"))),
                ContentModerationSupport.normalizeHitRetentionDays(ContentModerationSupport.readInteger(settings.get("hit_retention_days"))),
                ContentModerationSupport.normalizeNonHitRetentionDays(ContentModerationSupport.readInteger(settings.get("non_hit_retention_days"))),
                ContentModerationSupport.readBoolean(settings.get("pre_hash_check_enabled"), false),
                ContentModerationSupport.normalizeBlockedKeywords(settings.get("blocked_keywords")),
                ContentModerationSupport.normalizeKeywordBlockingMode(settings.get("keyword_blocking_mode")),
                thresholds,
                ContentModerationSupport.normalizeModelFilter(settings.get("model_filter"))
        );
        ContentModerationLoadedConfig config = new ContentModerationLoadedConfig(settings, response, apiKeys, thresholds);
        runtimeService.syncConfig(config);
        return config;
    }

    public ContentModerationConfigResponse updateConfig(UpdateContentModerationConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        Map<String, Object> settings = new LinkedHashMap<>(loadConfig().rawSettings());
        putIfPresent(settings, "enabled", request.enabled());
        putIfPresent(settings, "mode", request.mode());
        putIfPresent(settings, "base_url", request.base_url());
        putIfPresent(settings, "model", request.model());
        if (Boolean.TRUE.equals(request.clear_api_key())) {
            settings.put("api_keys", List.of());
            settings.remove("api_key");
        } else if (request.api_keys() != null) {
            settings.put("api_keys", ContentModerationSupport.normalizeApiKeys(request.api_keys()));
            settings.remove("api_key");
        } else if (ContentModerationSupport.trimToNull(request.api_key()) != null) {
            List<String> apiKeys = new ArrayList<>(ContentModerationSupport.extractApiKeys(settings));
            apiKeys.add(request.api_key().trim());
            settings.put("api_keys", ContentModerationSupport.normalizeApiKeys(apiKeys));
            settings.remove("api_key");
        }
        putIfPresent(settings, "timeout_ms", request.timeout_ms());
        putIfPresent(settings, "sample_rate", request.sample_rate());
        putIfPresent(settings, "all_groups", request.all_groups());
        if (request.group_ids() != null) {
            settings.put("group_ids", ContentModerationSupport.normalizeLongIds(request.group_ids()));
        }
        putIfPresent(settings, "record_non_hits", request.record_non_hits());
        putIfPresent(settings, "worker_count", request.worker_count());
        putIfPresent(settings, "queue_size", request.queue_size());
        putIfPresent(settings, "block_status", request.block_status());
        putIfPresent(settings, "block_message", request.block_message());
        putIfPresent(settings, "email_on_hit", request.email_on_hit());
        putIfPresent(settings, "auto_ban_enabled", request.auto_ban_enabled());
        putIfPresent(settings, "ban_threshold", request.ban_threshold());
        putIfPresent(settings, "violation_window_hours", request.violation_window_hours());
        putIfPresent(settings, "retry_count", request.retry_count());
        putIfPresent(settings, "hit_retention_days", request.hit_retention_days());
        putIfPresent(settings, "non_hit_retention_days", request.non_hit_retention_days());
        putIfPresent(settings, "pre_hash_check_enabled", request.pre_hash_check_enabled());
        if (request.blocked_keywords() != null) {
            settings.put("blocked_keywords", request.blocked_keywords());
        }
        if (request.keyword_blocking_mode() != null) {
            settings.put("keyword_blocking_mode", request.keyword_blocking_mode());
        }
        if (request.model_filter() != null) {
            settings.put("model_filter", request.model_filter());
        }
        ContentModerationSupport.normalizeConfigMap(settings);
        settingsRepository.upsertSettingValue(ContentModerationSupport.CONFIG_KEY, jsonHelper.writeJson(settings));
        return loadConfig().response();
    }

    public boolean isRiskControlEnabled() {
        return ContentModerationSupport.readBoolean(
                settingsRepository.getSettingValue(ContentModerationSupport.RISK_CONTROL_ENABLED_KEY),
                false
        );
    }

    private Map<String, Object> loadSettings() {
        String raw = settingsRepository.getSettingValue(ContentModerationSupport.CONFIG_KEY);
        if (ContentModerationSupport.trimToNull(raw) == null) {
            raw = settingsRepository.getSettingValue(ContentModerationSupport.LEGACY_CONFIG_KEY);
        }
        return raw == null ? Map.of() : jsonHelper.readObjectMap(raw);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
