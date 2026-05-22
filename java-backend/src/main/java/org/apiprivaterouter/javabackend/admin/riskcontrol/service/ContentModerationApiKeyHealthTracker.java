package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationApiKeyStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ContentModerationApiKeyHealthTracker {

    private final Map<String, ApiKeyRuntimeState> apiKeyHealth = new ConcurrentHashMap<>();

    public void markSuccess(String apiKey, int latencyMs, int httpStatus) {
        String hash = ContentModerationSupport.hashApiKey(apiKey);
        if (hash == null) {
            return;
        }
        ApiKeyRuntimeState state = apiKeyHealth.computeIfAbsent(hash, unused -> new ApiKeyRuntimeState(ContentModerationSupport.maskApiKey(apiKey)));
        synchronized (state) {
            state.masked = ContentModerationSupport.maskApiKey(apiKey);
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

    public void markFailure(String apiKey, String error, int latencyMs, int httpStatus) {
        String hash = ContentModerationSupport.hashApiKey(apiKey);
        if (hash == null) {
            return;
        }
        ApiKeyRuntimeState state = apiKeyHealth.computeIfAbsent(hash, unused -> new ApiKeyRuntimeState(ContentModerationSupport.maskApiKey(apiKey)));
        synchronized (state) {
            state.masked = ContentModerationSupport.maskApiKey(apiKey);
            state.failureCount++;
            state.lastError = ContentModerationSupport.truncate(error, 180);
            state.lastCheckedAt = Instant.now();
            state.lastLatencyMs = latencyMs;
            state.lastHttpStatus = httpStatus;
            state.lastTested = true;
            if (state.failureCount >= ContentModerationSupport.API_KEY_FREEZE_THRESHOLD) {
                state.frozenUntil = Instant.now().plus(ContentModerationSupport.API_KEY_FREEZE_DURATION);
            }
        }
    }

    public List<ContentModerationApiKeyStatus> buildStatuses(List<String> apiKeys, boolean configured) {
        List<ContentModerationApiKeyStatus> items = new ArrayList<>(apiKeys.size());
        for (int index = 0; index < apiKeys.size(); index++) {
            items.add(buildStatus(index, apiKeys.get(index), configured));
        }
        return items;
    }

    public ContentModerationApiKeyStatus buildStatus(int index, String apiKey, boolean configured) {
        String hash = ContentModerationSupport.hashApiKey(apiKey);
        String masked = ContentModerationSupport.maskApiKey(apiKey);
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
            } else if (ContentModerationSupport.trimToNull(state.lastError) != null) {
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
                    state.lastError == null ? "" : state.lastError,
                    state.lastCheckedAt == null ? null : state.lastCheckedAt.toString(),
                    state.frozenUntil == null ? null : state.frozenUntil.toString(),
                    state.lastLatencyMs,
                    state.lastHttpStatus,
                    state.lastTested,
                    configured
            );
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
}
