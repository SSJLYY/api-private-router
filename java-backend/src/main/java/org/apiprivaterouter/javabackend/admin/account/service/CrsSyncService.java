package org.apiprivaterouter.javabackend.admin.account.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.CrsPreviewAccount;
import org.apiprivaterouter.javabackend.admin.account.model.PreviewFromCrsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.PreviewFromCrsResult;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsItemResult;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsResult;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class CrsSyncService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DISABLED = "disabled";
    private static final String STATUS_ERROR = "error";

    private final AdminAccountRepository accountRepository;
    private final AdminProxyRepository proxyRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public CrsSyncService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public PreviewFromCrsResult previewFromCrs(PreviewFromCrsRequest request) {
        CrsExportResponse exported = fetchCrsExport(request.base_url(), request.username(), request.password());
        Set<String> existingIds = accountRepository.listCrsAccountIds();

        List<CrsPreviewAccount> newAccounts = new ArrayList<>();
        List<CrsPreviewAccount> existingAccounts = new ArrayList<>();

        classify(exported.data().claudeAccounts(), existingIds, newAccounts, existingAccounts, item -> {
            String authType = blankToNull(item.authType());
            return new CrsPreviewAccount(item.id(), item.kind(), defaultName(item.name(), item.id()), "anthropic", authType == null ? "oauth" : authType);
        });
        classify(exported.data().claudeConsoleAccounts(), existingIds, newAccounts, existingAccounts, item ->
                new CrsPreviewAccount(item.id(), item.kind(), defaultName(item.name(), item.id()), "anthropic", "apikey"));
        classify(exported.data().openaiOAuthAccounts(), existingIds, newAccounts, existingAccounts, item ->
                new CrsPreviewAccount(item.id(), item.kind(), defaultName(item.name(), item.id()), "openai", "oauth"));
        classify(exported.data().openaiResponsesAccounts(), existingIds, newAccounts, existingAccounts, item ->
                new CrsPreviewAccount(item.id(), item.kind(), defaultName(item.name(), item.id()), "openai", "apikey"));
        classify(exported.data().geminiOAuthAccounts(), existingIds, newAccounts, existingAccounts, item ->
                new CrsPreviewAccount(item.id(), item.kind(), defaultName(item.name(), item.id()), "gemini", "oauth"));
        classify(exported.data().geminiApiKeyAccounts(), existingIds, newAccounts, existingAccounts, item ->
                new CrsPreviewAccount(item.id(), item.kind(), defaultName(item.name(), item.id()), "gemini", "apikey"));

        return new PreviewFromCrsResult(List.copyOf(newAccounts), List.copyOf(existingAccounts));
    }

    @Transactional
    public SyncFromCrsResult syncFromCrs(SyncFromCrsRequest request) {
        CrsExportResponse exported = fetchCrsExport(request.base_url(), request.username(), request.password());
        boolean syncProxies = request.sync_proxies() == null || request.sync_proxies();
        Map<String, Long> proxyIdByKey = new LinkedHashMap<>();
        if (syncProxies) {
            for (AdminProxyResponse proxy : proxyRepository.listAllActive(null, false)) {
                proxyIdByKey.put(buildProxyKey(proxy.protocol(), proxy.host(), proxy.port(), proxy.username(), proxy.password()), proxy.id());
            }
        }

        Set<String> selectedSet = request.selected_account_ids() == null
                ? null
                : new LinkedHashSet<>(request.selected_account_ids());
        String now = Instant.now().toString();
        SyncCounters counters = new SyncCounters();

        processClaudeAccounts(exported.data().claudeAccounts(), syncProxies, proxyIdByKey, selectedSet, now, counters);
        processClaudeConsoleAccounts(exported.data().claudeConsoleAccounts(), syncProxies, proxyIdByKey, selectedSet, now, counters);
        processOpenAiOAuthAccounts(exported.data().openaiOAuthAccounts(), syncProxies, proxyIdByKey, selectedSet, now, counters);
        processOpenAiResponsesAccounts(exported.data().openaiResponsesAccounts(), syncProxies, proxyIdByKey, selectedSet, now, counters);
        processGeminiOAuthAccounts(exported.data().geminiOAuthAccounts(), syncProxies, proxyIdByKey, selectedSet, now, counters);
        processGeminiApiKeyAccounts(exported.data().geminiApiKeyAccounts(), syncProxies, proxyIdByKey, selectedSet, now, counters);

        return new SyncFromCrsResult(
                counters.created,
                counters.updated,
                counters.skipped,
                counters.failed,
                List.copyOf(counters.items)
        );
    }

    private void processClaudeAccounts(
            List<CrsClaudeAccount> accounts,
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            Set<String> selectedSet,
            String now,
            SyncCounters counters
    ) {
        for (CrsClaudeAccount src : safeList(accounts)) {
            String targetType = blankToNull(src.authType());
            if (targetType == null) {
                targetType = "oauth";
            }
            if (!"oauth".equals(targetType) && !"setup-token".equals(targetType)) {
                counters.skip(src.id(), src.kind(), src.name(), "unsupported authType: " + targetType);
                continue;
            }
            String accessToken = stringValue(src.credentials() == null ? null : src.credentials().get("access_token"));
            if (accessToken == null) {
                counters.fail(src.id(), src.kind(), src.name(), "missing access_token");
                continue;
            }

            Long proxyId;
            try {
                proxyId = mapOrCreateProxy(syncProxies, proxyIdByKey, src.proxy(), "crs-" + defaultName(src.name(), src.id()));
            } catch (Exception ex) {
                counters.fail(src.id(), src.kind(), src.name(), "proxy sync failed: " + ex.getMessage());
                continue;
            }

            Map<String, Object> credentials = sanitizeCredentialsMap(src.credentials());
            cleanBaseUrl(credentials, "/v1");
            convertExpiresAtToUnix(credentials, false);
            credentials.putIfAbsent("intercept_warmup_requests", false);

            Map<String, Object> extra = sanitizeCredentialsMap(src.extra());
            extra.put("crs_account_id", src.id());
            extra.put("crs_kind", src.kind());
            extra.put("crs_synced_at", now);
            if (src.credentials() != null && src.credentials().containsKey("org_uuid")) {
                extra.put("org_uuid", src.credentials().get("org_uuid"));
            }
            if (src.credentials() != null && src.credentials().containsKey("account_uuid")) {
                extra.put("account_uuid", src.credentials().get("account_uuid"));
            }

            upsertSyncedAccount(
                    src.id(),
                    src.kind(),
                    src.name(),
                    "anthropic",
                    targetType,
                    credentials,
                    extra,
                    proxyId,
                    3,
                    clampPriority(src.priority()),
                    mapCrsStatus(src.isActive(), src.status()),
                    src.schedulable(),
                    selectedSet,
                    counters
            );
        }
    }

    private void processClaudeConsoleAccounts(
            List<CrsConsoleAccount> accounts,
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            Set<String> selectedSet,
            String now,
            SyncCounters counters
    ) {
        for (CrsConsoleAccount src : safeList(accounts)) {
            String apiKey = stringValue(src.credentials() == null ? null : src.credentials().get("api_key"));
            if (apiKey == null) {
                counters.fail(src.id(), src.kind(), src.name(), "missing api_key");
                continue;
            }

            Long proxyId;
            try {
                proxyId = mapOrCreateProxy(syncProxies, proxyIdByKey, src.proxy(), "crs-" + defaultName(src.name(), src.id()));
            } catch (Exception ex) {
                counters.fail(src.id(), src.kind(), src.name(), "proxy sync failed: " + ex.getMessage());
                continue;
            }

            Map<String, Object> credentials = sanitizeCredentialsMap(src.credentials());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("crs_account_id", src.id());
            extra.put("crs_kind", src.kind());
            extra.put("crs_synced_at", now);

            upsertSyncedAccount(
                    src.id(),
                    src.kind(),
                    src.name(),
                    "anthropic",
                    "apikey",
                    credentials,
                    extra,
                    proxyId,
                    src.maxConcurrentTasks() > 0 ? src.maxConcurrentTasks() : 3,
                    clampPriority(src.priority()),
                    mapCrsStatus(src.isActive(), src.status()),
                    src.schedulable(),
                    selectedSet,
                    counters
            );
        }
    }

    private void processOpenAiOAuthAccounts(
            List<CrsOpenAiOAuthAccount> accounts,
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            Set<String> selectedSet,
            String now,
            SyncCounters counters
    ) {
        for (CrsOpenAiOAuthAccount src : safeList(accounts)) {
            String accessToken = stringValue(src.credentials() == null ? null : src.credentials().get("access_token"));
            if (accessToken == null) {
                counters.fail(src.id(), src.kind(), src.name(), "missing access_token");
                continue;
            }

            Long proxyId;
            try {
                proxyId = mapOrCreateProxy(syncProxies, proxyIdByKey, src.proxy(), "crs-" + defaultName(src.name(), src.id()));
            } catch (Exception ex) {
                counters.fail(src.id(), src.kind(), src.name(), "proxy sync failed: " + ex.getMessage());
                continue;
            }

            Map<String, Object> credentials = sanitizeCredentialsMap(src.credentials());
            credentials.putIfAbsent("token_type", "Bearer");
            convertExpiresAtToUnix(credentials, false);

            Map<String, Object> extra = sanitizeCredentialsMap(src.extra());
            extra.put("crs_account_id", src.id());
            extra.put("crs_kind", src.kind());
            extra.put("crs_synced_at", now);
            if (src.extra() != null && src.extra().containsKey("crs_email")) {
                extra.put("email", src.extra().get("crs_email"));
            }

            upsertSyncedAccount(
                    src.id(),
                    src.kind(),
                    src.name(),
                    "openai",
                    "oauth",
                    credentials,
                    extra,
                    proxyId,
                    3,
                    clampPriority(src.priority()),
                    mapCrsStatus(src.isActive(), src.status()),
                    src.schedulable(),
                    selectedSet,
                    counters
            );
        }
    }

    private void processOpenAiResponsesAccounts(
            List<CrsOpenAiResponsesAccount> accounts,
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            Set<String> selectedSet,
            String now,
            SyncCounters counters
    ) {
        for (CrsOpenAiResponsesAccount src : safeList(accounts)) {
            String apiKey = stringValue(src.credentials() == null ? null : src.credentials().get("api_key"));
            if (apiKey == null) {
                counters.fail(src.id(), src.kind(), src.name(), "missing api_key");
                continue;
            }

            Long proxyId;
            try {
                proxyId = mapOrCreateProxy(syncProxies, proxyIdByKey, src.proxy(), "crs-" + defaultName(src.name(), src.id()));
            } catch (Exception ex) {
                counters.fail(src.id(), src.kind(), src.name(), "proxy sync failed: " + ex.getMessage());
                continue;
            }

            Map<String, Object> credentials = sanitizeCredentialsMap(src.credentials());
            if (stringValue(credentials.get("base_url")) == null) {
                credentials.put("base_url", "https://api.openai.com");
            }
            cleanBaseUrl(credentials, "/v1");

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("crs_account_id", src.id());
            extra.put("crs_kind", src.kind());
            extra.put("crs_synced_at", now);

            upsertSyncedAccount(
                    src.id(),
                    src.kind(),
                    src.name(),
                    "openai",
                    "apikey",
                    credentials,
                    extra,
                    proxyId,
                    3,
                    clampPriority(src.priority()),
                    mapCrsStatus(src.isActive(), src.status()),
                    src.schedulable(),
                    selectedSet,
                    counters
            );
        }
    }

    private void processGeminiOAuthAccounts(
            List<CrsGeminiOAuthAccount> accounts,
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            Set<String> selectedSet,
            String now,
            SyncCounters counters
    ) {
        for (CrsGeminiOAuthAccount src : safeList(accounts)) {
            String refreshToken = stringValue(src.credentials() == null ? null : src.credentials().get("refresh_token"));
            if (refreshToken == null) {
                counters.fail(src.id(), src.kind(), src.name(), "missing refresh_token");
                continue;
            }

            Long proxyId;
            try {
                proxyId = mapOrCreateProxy(syncProxies, proxyIdByKey, src.proxy(), "crs-" + defaultName(src.name(), src.id()));
            } catch (Exception ex) {
                counters.fail(src.id(), src.kind(), src.name(), "proxy sync failed: " + ex.getMessage());
                continue;
            }

            Map<String, Object> credentials = sanitizeCredentialsMap(src.credentials());
            credentials.putIfAbsent("token_type", "Bearer");
            convertExpiresAtToUnix(credentials, true);

            Map<String, Object> extra = sanitizeCredentialsMap(src.extra());
            extra.put("crs_account_id", src.id());
            extra.put("crs_kind", src.kind());
            extra.put("crs_synced_at", now);

            upsertSyncedAccount(
                    src.id(),
                    src.kind(),
                    src.name(),
                    "gemini",
                    "oauth",
                    credentials,
                    extra,
                    proxyId,
                    3,
                    clampPriority(src.priority()),
                    mapCrsStatus(src.isActive(), src.status()),
                    src.schedulable(),
                    selectedSet,
                    counters
            );
        }
    }

    private void processGeminiApiKeyAccounts(
            List<CrsGeminiApiKeyAccount> accounts,
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            Set<String> selectedSet,
            String now,
            SyncCounters counters
    ) {
        for (CrsGeminiApiKeyAccount src : safeList(accounts)) {
            String apiKey = stringValue(src.credentials() == null ? null : src.credentials().get("api_key"));
            if (apiKey == null) {
                counters.fail(src.id(), src.kind(), src.name(), "missing api_key");
                continue;
            }

            Long proxyId;
            try {
                proxyId = mapOrCreateProxy(syncProxies, proxyIdByKey, src.proxy(), "crs-" + defaultName(src.name(), src.id()));
            } catch (Exception ex) {
                counters.fail(src.id(), src.kind(), src.name(), "proxy sync failed: " + ex.getMessage());
                continue;
            }

            Map<String, Object> credentials = sanitizeCredentialsMap(src.credentials());
            if (stringValue(credentials.get("base_url")) == null) {
                credentials.put("base_url", "https://generativelanguage.googleapis.com");
            }

            Map<String, Object> extra = sanitizeCredentialsMap(src.extra());
            extra.put("crs_account_id", src.id());
            extra.put("crs_kind", src.kind());
            extra.put("crs_synced_at", now);

            upsertSyncedAccount(
                    src.id(),
                    src.kind(),
                    src.name(),
                    "gemini",
                    "apikey",
                    credentials,
                    extra,
                    proxyId,
                    3,
                    clampPriority(src.priority()),
                    mapCrsStatus(src.isActive(), src.status()),
                    src.schedulable(),
                    selectedSet,
                    counters
            );
        }
    }

    private void upsertSyncedAccount(
            String crsAccountId,
            String kind,
            String sourceName,
            String platform,
            String type,
            Map<String, Object> credentials,
            Map<String, Object> extra,
            Long proxyId,
            int concurrency,
            int priority,
            String status,
            boolean schedulable,
            Set<String> selectedSet,
            SyncCounters counters
    ) {
        try {
            Optional<AdminAccountResponse> existing = accountRepository.getAccountByCrsAccountId(crsAccountId);
            if (existing.isEmpty()) {
                if (selectedSet != null && !selectedSet.contains(crsAccountId)) {
                    counters.skip(crsAccountId, kind, sourceName, "not selected");
                    return;
                }
                Map<String, Object> normalizedCredentials = upstreamUrlGuard.normalizeAccountCredentials(platform, type, credentials);
                accountRepository.createAccount(
                        defaultName(sourceName, crsAccountId),
                        null,
                        platform,
                        type,
                        normalizedCredentials,
                        extra,
                        proxyId,
                        concurrency,
                        null,
                        priority,
                        null,
                        status,
                        schedulable,
                        null,
                        true
                );
                counters.create(crsAccountId, kind, sourceName);
                return;
            }

            AdminAccountResponse current = existing.get();
            Map<String, Object> mergedCredentials = mergeMaps(current.credentials(), credentials);
            mergedCredentials = upstreamUrlGuard.normalizeAccountCredentials(platform, type, mergedCredentials);
            Map<String, Object> mergedExtra = mergeMaps(current.extra(), extra);
            int updated = accountRepository.updateAccountForCrsSync(
                    current.id(),
                    defaultName(sourceName, crsAccountId),
                    platform,
                    type,
                    mergedCredentials,
                    mergedExtra,
                    proxyId != null,
                    proxyId,
                    concurrency,
                    priority,
                    status,
                    schedulable
            );
            if (updated == 0) {
                counters.fail(crsAccountId, kind, sourceName, "update failed: account not found");
                return;
            }
            counters.update(crsAccountId, kind, sourceName);
        } catch (Exception ex) {
            counters.fail(crsAccountId, kind, sourceName, ex.getMessage());
        }
    }

    private Long mapOrCreateProxy(
            boolean syncProxies,
            Map<String, Long> proxyIdByKey,
            CrsProxy src,
            String defaultName
    ) {
        if (!syncProxies || src == null) {
            return null;
        }
        String protocol = normalizeProxyProtocol(src.protocol());
        String host = blankToNull(src.host());
        String username = blankToNull(src.username());
        String password = blankToNull(src.password());
        int port = src.port();
        if (protocol == null || host == null || port <= 0) {
            return null;
        }

        String key = buildProxyKey(protocol, host, port, username, password);
        Long existingId = proxyIdByKey.get(key);
        if (existingId != null) {
            return existingId;
        }

        long createdId = proxyRepository.createProxy(
                defaultProxyName(defaultName, protocol, host, port),
                protocol,
                host,
                port,
                username,
                password
        );
        proxyIdByKey.put(key, createdId);
        return createdId;
    }

    private CrsExportResponse fetchCrsExport(String rawBaseUrl, String username, String password) {
        String baseUrl = normalizeBaseUrl(rawBaseUrl);
        String token = crsLogin(baseUrl, username, password);
        return crsExportAccounts(baseUrl, token);
    }

    private String crsLogin(String baseUrl, String username, String password) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/web/auth/login"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                            "username", username,
                            "password", password
                    )), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("crs login failed: status=" + response.statusCode() + " body=" + response.body());
            }
            CrsLoginResponse parsed = objectMapper.readValue(response.body(), CrsLoginResponse.class);
            if (!parsed.success() || blankToNull(parsed.token()) == null) {
                String message = blankToNull(parsed.message()) != null ? parsed.message() : parsed.error();
                throw new IllegalArgumentException("crs login failed: " + (message == null ? "unknown error" : message));
            }
            return parsed.token();
        } catch (IOException ex) {
            throw new IllegalArgumentException("crs login parse failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("crs login interrupted");
        }
    }

    private CrsExportResponse crsExportAccounts(String baseUrl, String adminToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/sync/export-accounts?include_secrets=true"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("crs export failed: status=" + response.statusCode() + " body=" + response.body());
            }
            CrsExportResponse parsed = objectMapper.readValue(response.body(), CrsExportResponse.class);
            if (!parsed.success()) {
                String message = blankToNull(parsed.message()) != null ? parsed.message() : parsed.error();
                throw new IllegalArgumentException("crs export failed: " + (message == null ? "unknown error" : message));
            }
            return parsed;
        } catch (IOException ex) {
            throw new IllegalArgumentException("crs export parse failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("crs export interrupted");
        }
    }

    private String normalizeBaseUrl(String raw) {
        return upstreamUrlGuard.normalizeCrsBaseUrl(raw);
    }

    private <T extends CrsIdentified> void classify(
            List<T> source,
            Set<String> existingIds,
            List<CrsPreviewAccount> newAccounts,
            List<CrsPreviewAccount> existingAccounts,
            java.util.function.Function<T, CrsPreviewAccount> mapper
    ) {
        for (T item : safeList(source)) {
            CrsPreviewAccount preview = mapper.apply(item);
            if (existingIds.contains(item.id())) {
                existingAccounts.add(preview);
            } else {
                newAccounts.add(preview);
            }
        }
    }

    private Map<String, Object> sanitizeCredentialsMap(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        input.forEach((key, value) -> {
            if (value != null) {
                out.put(key, value);
            }
        });
        return out;
    }

    private Map<String, Object> mergeMaps(Map<String, Object> existing, Map<String, Object> updates) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (existing != null) {
            out.putAll(existing);
        }
        if (updates != null) {
            out.putAll(updates);
        }
        return out;
    }

    private void cleanBaseUrl(Map<String, Object> credentials, String suffixToRemove) {
        String baseUrl = stringValue(credentials.get("base_url"));
        if (baseUrl != null && baseUrl.endsWith(suffixToRemove)) {
            credentials.put("base_url", baseUrl.substring(0, baseUrl.length() - suffixToRemove.length()));
        }
    }

    private void convertExpiresAtToUnix(Map<String, Object> credentials, boolean asString) {
        String expiresAt = stringValue(credentials.get("expires_at"));
        if (expiresAt == null) {
            return;
        }
        try {
            long unix = Instant.parse(expiresAt).getEpochSecond();
            credentials.put("expires_at", asString ? Long.toString(unix) : unix);
        } catch (DateTimeParseException ignored) {
        }
    }

    private String buildProxyKey(String protocol, String host, int port, String username, String password) {
        return "%s://%s:%d?u=%s&p=%s".formatted(
                protocol == null ? "" : protocol,
                host == null ? "" : host,
                port,
                username == null ? "" : username,
                password == null ? "" : password
        );
    }

    private String defaultProxyName(String base, String protocol, String host, int port) {
        String normalized = blankToNull(base);
        if (normalized == null) {
            normalized = "crs";
        }
        return "%s (%s://%s:%d)".formatted(normalized, protocol, host, port);
    }

    private String normalizeProxyProtocol(String protocol) {
        String normalized = blankToNull(protocol);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "http", "https", "socks5" -> normalized;
            case "socks", "socks5h" -> "socks5";
            default -> null;
        };
    }

    private String defaultName(String name, String id) {
        String normalized = blankToNull(name);
        return normalized != null ? normalized : "CRS " + id;
    }

    private int clampPriority(int priority) {
        return priority < 1 || priority > 100 ? 50 : priority;
    }

    private String mapCrsStatus(boolean active, String status) {
        if (!active) {
            return STATUS_DISABLED;
        }
        return "error".equalsIgnoreCase(blankToNull(status)) ? STATUS_ERROR : STATUS_ACTIVE;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static final class SyncCounters {
        private int created;
        private int updated;
        private int skipped;
        private int failed;
        private final List<SyncFromCrsItemResult> items = new ArrayList<>();

        private void create(String id, String kind, String name) {
            created++;
            items.add(new SyncFromCrsItemResult(id, kind, name, "created", null));
        }

        private void update(String id, String kind, String name) {
            updated++;
            items.add(new SyncFromCrsItemResult(id, kind, name, "updated", null));
        }

        private void skip(String id, String kind, String name, String error) {
            skipped++;
            items.add(new SyncFromCrsItemResult(id, kind, name, "skipped", error));
        }

        private void fail(String id, String kind, String name, String error) {
            failed++;
            items.add(new SyncFromCrsItemResult(id, kind, name, "failed", error));
        }
    }

    private interface CrsIdentified {
        String id();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsLoginResponse(boolean success, String token, String message, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsExportResponse(boolean success, String error, String message, CrsExportData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsExportData(
            List<CrsClaudeAccount> claudeAccounts,
            List<CrsConsoleAccount> claudeConsoleAccounts,
            List<CrsOpenAiOAuthAccount> openaiOAuthAccounts,
            List<CrsOpenAiResponsesAccount> openaiResponsesAccounts,
            List<CrsGeminiOAuthAccount> geminiOAuthAccounts,
            List<CrsGeminiApiKeyAccount> geminiApiKeyAccounts
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsProxy(String protocol, String host, int port, String username, String password) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsClaudeAccount(
            String kind,
            String id,
            String name,
            String description,
            String platform,
            String authType,
            boolean isActive,
            boolean schedulable,
            int priority,
            String status,
            CrsProxy proxy,
            Map<String, Object> credentials,
            Map<String, Object> extra
    ) implements CrsIdentified {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsConsoleAccount(
            String kind,
            String id,
            String name,
            String description,
            String platform,
            boolean isActive,
            boolean schedulable,
            int priority,
            String status,
            int maxConcurrentTasks,
            CrsProxy proxy,
            Map<String, Object> credentials
    ) implements CrsIdentified {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsOpenAiResponsesAccount(
            String kind,
            String id,
            String name,
            String description,
            String platform,
            boolean isActive,
            boolean schedulable,
            int priority,
            String status,
            CrsProxy proxy,
            Map<String, Object> credentials
    ) implements CrsIdentified {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsOpenAiOAuthAccount(
            String kind,
            String id,
            String name,
            String description,
            String platform,
            String authType,
            boolean isActive,
            boolean schedulable,
            int priority,
            String status,
            CrsProxy proxy,
            Map<String, Object> credentials,
            Map<String, Object> extra
    ) implements CrsIdentified {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsGeminiOAuthAccount(
            String kind,
            String id,
            String name,
            String description,
            String platform,
            String authType,
            boolean isActive,
            boolean schedulable,
            int priority,
            String status,
            CrsProxy proxy,
            Map<String, Object> credentials,
            Map<String, Object> extra
    ) implements CrsIdentified {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CrsGeminiApiKeyAccount(
            String kind,
            String id,
            String name,
            String description,
            String platform,
            boolean isActive,
            boolean schedulable,
            int priority,
            String status,
            CrsProxy proxy,
            Map<String, Object> credentials,
            Map<String, Object> extra
    ) implements CrsIdentified {
    }
}
