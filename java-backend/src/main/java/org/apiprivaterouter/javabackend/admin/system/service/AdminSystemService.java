package org.apiprivaterouter.javabackend.admin.system.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.admin.system.model.SystemReleaseAssetResponse;
import org.apiprivaterouter.javabackend.admin.system.model.SystemReleaseInfoResponse;
import org.apiprivaterouter.javabackend.admin.system.model.SystemUpdateInfoResponse;
import org.apiprivaterouter.javabackend.admin.system.model.SystemVersionResponse;
import org.apiprivaterouter.javabackend.admin.system.repository.AdminSystemRepository;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminSystemService {

    private static final String LOCK_STATUS_PROCESSING = "processing";
    private static final String DEFAULT_BUILD_TYPE = "source";
    private static final String DEFAULT_VERSION = "dev";
    private static final String DEFAULT_RELEASE_CHECK_URL = "";
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofHours(1);
    private static final Duration LOCK_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final AdminSystemRepository repository;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AdminSystemService(
            AdminSystemRepository repository,
            Environment environment,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public SystemVersionResponse getVersion() {
        return new SystemVersionResponse(resolveVersion());
    }

    public SystemUpdateInfoResponse checkUpdates(boolean force) {
        String releaseCheckUrl = resolveReleaseCheckUrl();
        if (releaseCheckUrl.isBlank()) {
            String currentVersion = resolveVersion();
            return new SystemUpdateInfoResponse(
                    currentVersion,
                    currentVersion,
                    false,
                    null,
                    false,
                    "release check disabled",
                    resolveBuildType()
            );
        }
        GitHubRelease release = fetchLatestRelease();
        String currentVersion = resolveVersion();
        String latestVersion = normalizeVersion(release.tagName());
        return new SystemUpdateInfoResponse(
                currentVersion,
                latestVersion,
                compareVersions(currentVersion, latestVersion) < 0,
                new SystemReleaseInfoResponse(
                        trimToEmpty(release.name()),
                        trimToEmpty(release.body()),
                        trimToEmpty(release.publishedAt()),
                        trimToEmpty(release.htmlUrl()),
                        mapAssets(release.assets())
                ),
                false,
                "",
                resolveBuildType()
        );
    }

    public Map<String, Object> performUpdate(String idempotencyKey, long actorUserId, String route) {
        return executeLockedOperation("update", idempotencyKey, actorUserId, route, "SYSTEM_UPDATE_FAILED", true, "Update completed. Please restart the service.");
    }

    public Map<String, Object> rollback(String idempotencyKey, long actorUserId, String route) {
        return executeLockedOperation("rollback", idempotencyKey, actorUserId, route, "SYSTEM_ROLLBACK_FAILED", true, "Rollback completed. Please restart the service.");
    }

    public Map<String, Object> restart(String idempotencyKey, long actorUserId, String route) {
        return executeLockedOperation("restart", idempotencyKey, actorUserId, route, "", false, "Service restart initiated");
    }

    private Map<String, Object> executeLockedOperation(
            String operation,
            String idempotencyKey,
            long actorUserId,
            String route,
            String failureReason,
            boolean needRestart,
            String message
    ) {
        String operationId = buildOperationId(operation, idempotencyKey, actorUserId, route);
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(LOCK_LEASE);
        Instant expiresAt = now.plus(LOCK_TTL);

        AdminSystemRepository.SystemOperationRecord existing = repository.findLockRecord().orElse(null);
        long recordId;
        if (existing == null) {
            if (!repository.tryCreateProcessing(operationId, lockedUntil, expiresAt)) {
                throw busyError(repository.findLockRecord().orElse(null), now);
            }
            recordId = repository.findLockRecord().map(AdminSystemRepository.SystemOperationRecord::id).orElseThrow();
        } else if (LOCK_STATUS_PROCESSING.equalsIgnoreCase(trimToEmpty(existing.status()))
                && existing.lockedUntil() != null
                && existing.lockedUntil().isAfter(now)) {
            throw busyError(existing, now);
        } else if (repository.tryReclaim(existing.id(), trimToEmpty(existing.status()), now, lockedUntil, expiresAt, operationId)) {
            recordId = existing.id();
        } else {
            throw busyError(repository.findLockRecord().orElse(existing), now);
        }

        Map<String, Object> result = Map.of(
                "message", message,
                "need_restart", needRestart,
                "operation_id", operationId
        );
        try {
            repository.markSucceeded(recordId, operationId, writeJson(result), expiresAt);
            return result;
        } catch (RuntimeException ex) {
            repository.markFailed(recordId, operationId, failureReason.isBlank() ? "SYSTEM_OPERATION_FAILED" : failureReason, now.plus(LOCK_RETRY_BACKOFF), expiresAt);
            throw ex;
        }
    }

    private StructuredApiErrorException busyError(AdminSystemRepository.SystemOperationRecord record, Instant now) {
        String operationId = record == null ? "" : trimToEmpty(record.requestFingerprint());
        int retryAfter = 1;
        if (record != null && record.lockedUntil() != null) {
            retryAfter = Math.max(1, (int) Duration.between(now, record.lockedUntil()).getSeconds());
        }
        return new StructuredApiErrorException(409, "SYSTEM_OPERATION_BUSY", "another system operation is in progress", Map.of(
                "operation_id", operationId,
                "retry_after", String.valueOf(retryAfter)
        ));
    }

    private String buildOperationId(String operation, String idempotencyKey, long actorUserId, String route) {
        String normalizedKey = trimToEmpty(idempotencyKey);
        if (normalizedKey.isBlank()) {
            return "sysop-" + operation + "-" + Long.toString(System.nanoTime(), 36);
        }
        String seed = operation + "|" + actorUserId + "|" + trimToEmpty(route) + "|" + normalizedKey;
        String hash = Integer.toHexString(seed.hashCode());
        return "sysop-" + hash.replace("-", "n");
    }

    private GitHubRelease fetchLatestRelease() {
        String releaseCheckUrl = resolveReleaseCheckUrl();
        if (releaseCheckUrl.isBlank()) {
            throw new StructuredApiErrorException(500, "SYSTEM_UPDATE_CHECK_DISABLED", "release check disabled");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(releaseCheckUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "api-private-router-java-backend")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(500, "SYSTEM_UPDATE_CHECK_FAILED", "failed to check updates");
            }
            return objectMapper.readValue(response.body(), GitHubRelease.class);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(500, "SYSTEM_UPDATE_CHECK_FAILED", "failed to check updates");
        }
    }

    private String resolveReleaseCheckUrl() {
        return firstNonBlank(
                environment.getProperty("api-private-router.release-check-url"),
                environment.getProperty("API_PRIVATE_ROUTER_RELEASE_CHECK_URL"),
                environment.getProperty("app.release-check-url"),
                environment.getProperty("APP_RELEASE_CHECK_URL"),
                DEFAULT_RELEASE_CHECK_URL
        );
    }

    private List<SystemReleaseAssetResponse> mapAssets(List<GitHubAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        return assets.stream()
                .map(asset -> new SystemReleaseAssetResponse(
                        trimToEmpty(asset.name()),
                        trimToEmpty(asset.browserDownloadUrl()),
                        asset.size()
                ))
                .toList();
    }

    private String resolveVersion() {
        return firstNonBlank(
                environment.getProperty("api-private-router.version"),
                environment.getProperty("API_PRIVATE_ROUTER_VERSION"),
                environment.getProperty("app.version"),
                environment.getProperty("APP_VERSION"),
                AdminSystemService.class.getPackage().getImplementationVersion(),
                DEFAULT_VERSION
        );
    }

    private String resolveBuildType() {
        return firstNonBlank(
                environment.getProperty("api-private-router.build-type"),
                environment.getProperty("API_PRIVATE_ROUTER_BUILD_TYPE"),
                environment.getProperty("app.build-type"),
                environment.getProperty("APP_BUILD_TYPE"),
                DEFAULT_BUILD_TYPE
        );
    }

    private int compareVersions(String current, String latest) {
        int[] currentParts = parseVersion(current);
        int[] latestParts = parseVersion(latest);
        for (int i = 0; i < 3; i++) {
            if (currentParts[i] < latestParts[i]) {
                return -1;
            }
            if (currentParts[i] > latestParts[i]) {
                return 1;
            }
        }
        return 0;
    }

    private int[] parseVersion(String rawVersion) {
        int[] result = new int[]{0, 0, 0};
        String[] parts = normalizeVersion(rawVersion).split("\\.");
        for (int i = 0; i < parts.length && i < 3; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    private String normalizeVersion(String value) {
        String normalized = trimToEmpty(value);
        return normalized.startsWith("v") ? normalized.substring(1) : normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            return "{}";
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubRelease(
            @JsonProperty("tag_name") String tagName,
            @JsonProperty("name") String name,
            @JsonProperty("body") String body,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("assets") List<GitHubAsset> assets
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubAsset(
            @JsonProperty("name") String name,
            @JsonProperty("browser_download_url") String browserDownloadUrl,
            @JsonProperty("size") long size
    ) {
    }
}
