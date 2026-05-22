package org.apiprivaterouter.javabackend.channelmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorAvailabilityStat;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorCheckResult;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorHistoryEntry;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorLatestStatus;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorRecord;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorSummary;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorUpdateCommand;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorWriteRequest;
import org.apiprivaterouter.javabackend.channelmonitor.model.ExtraModelStatus;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorDetailRecord;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorModelDetail;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorTimelinePoint;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorViewRecord;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
public class ChannelMonitorCoreService {

    private static final Logger log = LoggerFactory.getLogger(ChannelMonitorCoreService.class);
    private static final Set<String> ALLOWED_PROVIDERS = Set.of("openai", "anthropic", "gemini");
    private static final Set<String> ALLOWED_STATUSES = Set.of("operational", "degraded", "failed", "error");
    private static final Set<String> ALLOWED_BODY_MODES = Set.of("off", "merge", "replace");
    private static final Set<String> FORBIDDEN_HEADER_NAMES = Set.of(
            "host", "content-length", "content-encoding", "transfer-encoding", "connection"
    );
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "localhost.localdomain",
            "metadata",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "instance-data.ec2.internal"
    );
    private static final Set<String> FEATURE_EMPTY_LIST_KEYS = Set.of("channel_monitor_enabled");
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9!#$%&'*+\\-.^_`|~]+$");
    private static final Pattern CHALLENGE_NUMBER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN = Pattern.compile("(?i)([?&](?:key|api[_-]?key|access[_-]?token|token|authorization|x-api-key)=)[^&\\s\"']+");
    private static final Pattern OPENAI_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9-]{20,}");
    private static final Pattern ANTHROPIC_KEY_PATTERN = Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}");
    private static final Pattern GEMINI_KEY_PATTERN = Pattern.compile("AIza[A-Za-z0-9_-]{35}");
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final String CHALLENGE_PROMPT_TEMPLATE = """
            Calculate and respond with ONLY the number, nothing else.

            Q: 3 + 5 = ?
            A: 8

            Q: 12 - 7 = ?
            A: 5

            Q: %d %s %d = ?
            A:""";
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int MAX_HISTORY_LIMIT = 1000;
    private static final int TIMELINE_LIMIT = 60;
    private static final int MAX_TRANSIENT_RETRIES = 1;
    private static final Duration DEGRADED_THRESHOLD = Duration.ofSeconds(6);
    private static final Duration RETRY_BACKOFF_BASE = Duration.ofMillis(250);

    private final org.apiprivaterouter.javabackend.channelmonitor.repository.ChannelMonitorCoreRepository repository;
    private final ChannelMonitorCrypto crypto;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ChannelMonitorCoreService(
            org.apiprivaterouter.javabackend.channelmonitor.repository.ChannelMonitorCoreRepository repository,
            ChannelMonitorCrypto crypto,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    public ChannelMonitorRecord create(ChannelMonitorWriteRequest request) {
        String bodyMode = resolveBodyMode(request.bodyOverrideMode());
        Map<String, Object> bodyOverride = normalizeBody(request.bodyOverride());
        Map<String, String> extraHeaders = normalizeHeaders(request.extraHeaders());
        validateWrite(
                request.provider(),
                request.endpoint(),
                request.primaryModel(),
                request.intervalSeconds(),
                request.apiKeyPlaintext(),
                bodyMode,
                bodyOverride
        );
        return repository.create(new ChannelMonitorWriteRequest(
                requireName(request.name()),
                normalizeProvider(request.provider()),
                normalizeEndpoint(request.endpoint()),
                crypto.encrypt(request.apiKeyPlaintext().trim()),
                requirePrimaryModel(request.primaryModel()),
                normalizeModels(request.extraModels()),
                normalizeGroupName(request.groupName()),
                request.enabled(),
                requireInterval(request.intervalSeconds()),
                request.createdBy(),
                request.templateId(),
                extraHeaders,
                bodyMode,
                bodyOverride
        ));
    }

    public ChannelMonitorRecord update(long id, ChannelMonitorUpdateCommand command) {
        ChannelMonitorRecord current = requireMonitor(id);
        String provider = command.providerPresent() ? normalizeProvider(command.provider()) : current.provider();
        String endpoint = command.endpointPresent() ? normalizeEndpoint(command.endpoint()) : current.endpoint();
        String primaryModel = command.primaryModelPresent() ? requirePrimaryModel(command.primaryModel()) : current.primaryModel();
        int intervalSeconds = command.intervalPresent() ? requireInterval(command.intervalSeconds()) : current.intervalSeconds();
        Map<String, String> extraHeaders = command.extraHeadersPresent() ? normalizeHeaders(command.extraHeaders()) : current.extraHeaders();
        String bodyMode = resolveBodyMode(command.bodyOverrideModePresent() ? command.bodyOverrideMode() : current.bodyOverrideMode());
        Map<String, Object> bodyOverride = command.bodyOverridePresent() ? normalizeBody(command.bodyOverride()) : current.bodyOverride();
        validateWrite(provider, endpoint, primaryModel, intervalSeconds, null, bodyMode, bodyOverride);

        String apiKeyEncrypted = current.apiKeyEncrypted();
        if (command.apiKeyPresent() && command.apiKeyPlaintext() != null && !command.apiKeyPlaintext().trim().isEmpty()) {
            apiKeyEncrypted = crypto.encrypt(command.apiKeyPlaintext().trim());
        }

        return repository.update(id, new ChannelMonitorRecord(
                current.id(),
                command.namePresent() ? requireName(command.name()) : current.name(),
                provider,
                endpoint,
                apiKeyEncrypted,
                primaryModel,
                command.extraModelsPresent() ? normalizeModels(command.extraModels()) : current.extraModels(),
                command.groupNamePresent() ? normalizeGroupName(command.groupName()) : current.groupName(),
                command.enabledPresent() ? Boolean.TRUE.equals(command.enabled()) : current.enabled(),
                intervalSeconds,
                current.lastCheckedAt(),
                current.createdBy(),
                current.createdAt(),
                current.updatedAt(),
                command.clearTemplate() ? null : command.templatePresent() ? command.templateId() : current.templateId(),
                extraHeaders,
                bodyMode,
                bodyOverride
        ));
    }

    public void delete(long id) {
        requireMonitor(id);
        repository.delete(id);
    }

    public ChannelMonitorRecord requireMonitor(long id) {
        return repository.findById(id).orElseThrow(() -> new HttpStatusException(404, "channel monitor not found"));
    }

    public int normalizeHistoryLimit(Integer raw) {
        if (raw == null || raw <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(raw, MAX_HISTORY_LIMIT);
    }

    public String decryptMaskedApiKey(ChannelMonitorRecord record) {
        String plain = decrypt(record);
        if (plain.length() <= 4) {
            return "***";
        }
        return plain.substring(0, 4) + "***";
    }

    public boolean decryptFailed(ChannelMonitorRecord record) {
        try {
            decrypt(record);
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    public String decrypt(ChannelMonitorRecord record) {
        return crypto.decrypt(record.apiKeyEncrypted());
    }

    public Map<Long, ChannelMonitorSummary> buildSummaries(List<ChannelMonitorRecord> records) {
        List<Long> ids = records.stream().map(ChannelMonitorRecord::id).toList();
        Map<Long, List<ChannelMonitorLatestStatus>> latestByMonitor =
                repository.findLatestStatusesByMonitorIds(ids).stream().collect(Collectors.groupingBy(
                        ChannelMonitorLatestStatus::monitorId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<Long, List<ChannelMonitorAvailabilityStat>> availabilityByMonitor =
                repository.findAvailabilityByMonitorIds(ids, 7).stream().collect(Collectors.groupingBy(
                        ChannelMonitorAvailabilityStat::monitorId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<Long, ChannelMonitorSummary> out = new LinkedHashMap<>();
        for (ChannelMonitorRecord record : records) {
            Map<String, ChannelMonitorLatestStatus> latest = latestByMonitor.getOrDefault(record.id(), List.of()).stream()
                    .collect(Collectors.toMap(ChannelMonitorLatestStatus::model, item -> item, (left, right) -> left, LinkedHashMap::new));
            Map<String, ChannelMonitorAvailabilityStat> availability = availabilityByMonitor.getOrDefault(record.id(), List.of()).stream()
                    .collect(Collectors.toMap(ChannelMonitorAvailabilityStat::model, item -> item, (left, right) -> left, LinkedHashMap::new));

            ChannelMonitorLatestStatus primaryLatest = latest.get(record.primaryModel());
            ChannelMonitorAvailabilityStat primaryAvailability = availability.get(record.primaryModel());
            List<ExtraModelStatus> extras = new ArrayList<>();
            for (String model : record.extraModels()) {
                ChannelMonitorLatestStatus extraLatest = latest.get(model);
                extras.add(new ExtraModelStatus(
                        model,
                        extraLatest == null ? "" : extraLatest.status(),
                        extraLatest == null ? null : extraLatest.latencyMs()
                ));
            }
            out.put(record.id(), new ChannelMonitorSummary(
                    primaryLatest == null ? "" : primaryLatest.status(),
                    primaryLatest == null ? null : primaryLatest.latencyMs(),
                    primaryLatest == null ? null : primaryLatest.pingLatencyMs(),
                    primaryAvailability == null ? 0D : primaryAvailability.availabilityPct(),
                    extras
            ));
        }
        return out;
    }

    public List<UserMonitorViewRecord> buildUserViews() {
        if (!isFeatureEnabled()) {
            return List.of();
        }
        List<ChannelMonitorRecord> records = repository.listEnabled();
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> ids = records.stream().map(ChannelMonitorRecord::id).toList();
        Map<Long, ChannelMonitorSummary> summaries = buildSummaries(records);
        Map<Long, List<ChannelMonitorHistoryEntry>> timelines = repository.findRecentPrimaryHistory(ids, records.stream()
                .collect(Collectors.toMap(ChannelMonitorRecord::id, ChannelMonitorRecord::primaryModel, (left, right) -> left, LinkedHashMap::new)), TIMELINE_LIMIT)
                .stream()
                .collect(Collectors.groupingBy(ChannelMonitorHistoryEntry::monitorId, LinkedHashMap::new, Collectors.toList()));

        List<UserMonitorViewRecord> items = new ArrayList<>();
        for (ChannelMonitorRecord record : records) {
            ChannelMonitorSummary summary = summaries.get(record.id());
            List<UserMonitorTimelinePoint> timeline = timelines.getOrDefault(record.id(), List.of()).stream()
                    .map(entry -> new UserMonitorTimelinePoint(entry.status(), entry.latencyMs(), entry.pingLatencyMs(), entry.checkedAt()))
                    .toList();
            items.add(new UserMonitorViewRecord(
                    record.id(),
                    record.name(),
                    record.provider(),
                    record.groupName(),
                    record.primaryModel(),
                    summary == null ? "" : summary.primaryStatus(),
                    summary == null ? null : summary.primaryLatencyMs(),
                    summary == null ? null : summary.primaryPingLatencyMs(),
                    summary == null ? 0D : summary.availability7d(),
                    summary == null ? List.of() : summary.extraModels(),
                    timeline
            ));
        }
        return items;
    }

    public UserMonitorDetailRecord buildUserDetail(long id) {
        if (!isFeatureEnabled()) {
            throw new HttpStatusException(404, "channel monitor not found");
        }
        ChannelMonitorRecord record = requireMonitor(id);
        if (!record.enabled()) {
            throw new HttpStatusException(404, "channel monitor not found");
        }
        Map<String, ChannelMonitorLatestStatus> latestByModel = repository.findLatestStatuses(id).stream()
                .collect(Collectors.toMap(ChannelMonitorLatestStatus::model, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<Integer, Map<String, ChannelMonitorAvailabilityStat>> availabilityByWindow = Map.of(
                7, repository.findAvailability(id, 7).stream().collect(Collectors.toMap(ChannelMonitorAvailabilityStat::model, item -> item, (left, right) -> left, LinkedHashMap::new)),
                15, repository.findAvailability(id, 15).stream().collect(Collectors.toMap(ChannelMonitorAvailabilityStat::model, item -> item, (left, right) -> left, LinkedHashMap::new)),
                30, repository.findAvailability(id, 30).stream().collect(Collectors.toMap(ChannelMonitorAvailabilityStat::model, item -> item, (left, right) -> left, LinkedHashMap::new))
        );

        List<String> models = new ArrayList<>();
        models.add(record.primaryModel());
        models.addAll(record.extraModels());
        List<UserMonitorModelDetail> detailItems = new ArrayList<>();
        for (String model : models) {
            ChannelMonitorLatestStatus latest = latestByModel.get(model);
            ChannelMonitorAvailabilityStat day7 = availabilityByWindow.get(7).get(model);
            ChannelMonitorAvailabilityStat day15 = availabilityByWindow.get(15).get(model);
            ChannelMonitorAvailabilityStat day30 = availabilityByWindow.get(30).get(model);
            detailItems.add(new UserMonitorModelDetail(
                    model,
                    latest == null ? "" : latest.status(),
                    latest == null ? null : latest.latencyMs(),
                    day7 == null ? 0D : day7.availabilityPct(),
                    day15 == null ? 0D : day15.availabilityPct(),
                    day30 == null ? 0D : day30.availabilityPct(),
                    day7 == null ? null : day7.avgLatencyMs()
            ));
        }

        return new UserMonitorDetailRecord(record.id(), record.name(), record.provider(), record.groupName(), detailItems);
    }

    public List<ChannelMonitorCheckResult> runNow(long id) {
        ChannelMonitorRecord record = requireMonitor(id);
        return runMonitor(record, true);
    }

    public List<ChannelMonitorRecord> claimDueMonitors(Instant now, int limit) {
        return repository.claimDueMonitors(now, limit);
    }

    public List<ChannelMonitorCheckResult> runClaimedMonitor(ChannelMonitorRecord record) {
        return runMonitor(record, false);
    }

    public boolean isFeatureEnabled() {
        String raw = repository.findSettingValue("channel_monitor_enabled");
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw);
    }

    private Integer ping(String endpoint) {
        try {
            ensurePublicEndpoint(endpoint);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(8))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            Instant start = Instant.now();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return (int) Duration.between(start, Instant.now()).toMillis();
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ChannelMonitorCheckResult> runMonitor(ChannelMonitorRecord record, boolean updateLastCheckedAt) {
        String apiKey;
        try {
            apiKey = decrypt(record);
        } catch (RuntimeException ex) {
            throw new HttpStatusException(500, "api key decryption failed; please re-edit the monitor with a fresh key");
        }
        List<String> models = new ArrayList<>();
        models.add(record.primaryModel());
        models.addAll(record.extraModels());
        Instant checkedAt = Instant.now();
        try {
            ensurePublicEndpoint(record.endpoint());
        } catch (HttpStatusException ex) {
            List<ChannelMonitorCheckResult> blocked = models.stream()
                    .map(model -> new ChannelMonitorCheckResult(model, "error", null, null, truncate(ex.getMessage()), checkedAt))
                    .toList();
            repository.insertHistory(record.id(), blocked);
            if (updateLastCheckedAt) {
                repository.markChecked(record.id(), checkedAt);
            }
            return blocked;
        }
        Integer pingLatencyMs = ping(record.endpoint());
        List<ChannelMonitorCheckResult> results = new ArrayList<>();
        for (String model : models) {
            results.add(runSingle(record, apiKey, model, pingLatencyMs, checkedAt));
        }
        repository.insertHistory(record.id(), results);
        if (updateLastCheckedAt) {
            repository.markChecked(record.id(), checkedAt);
        }
        return results;
    }

    private ChannelMonitorCheckResult runSingle(
            ChannelMonitorRecord record,
            String apiKey,
            String model,
            Integer pingLatencyMs,
            Instant checkedAt
    ) {
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            Instant startedAt = Instant.now();
            try {
                ProviderRequest providerRequest = buildProviderRequest(record, apiKey, model);
                HttpResponse<String> response = httpClient.send(providerRequest.request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int latencyMs = (int) Duration.between(startedAt, Instant.now()).toMillis();
                String body = response.body() == null ? "" : response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (shouldRetry(response.statusCode()) && attempt < MAX_TRANSIENT_RETRIES) {
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    String message = buildUpstreamErrorMessage(response.statusCode(), body);
                    return new ChannelMonitorCheckResult(model, "error", latencyMs, pingLatencyMs, message, checkedAt);
                }
                String extracted = extractText(body, providerRequest.textPath());
                if (providerRequest.skipChallengeValidation()) {
                    if (extracted == null || extracted.isBlank()) {
                        return new ChannelMonitorCheckResult(model, "failed", latencyMs, pingLatencyMs,
                                truncate("replace-mode: upstream returned 2xx with empty text"), checkedAt);
                    }
                } else if (!validateChallenge(extracted, providerRequest.expectedAnswer())) {
                    return new ChannelMonitorCheckResult(model, "failed", latencyMs, pingLatencyMs,
                            truncate(sanitizeErrorMessage("challenge mismatch (expected "
                                    + providerRequest.expectedAnswer() + ", got " + String.valueOf(extracted) + ")")), checkedAt);
                }
                String status = latencyMs >= DEGRADED_THRESHOLD.toMillis() ? "degraded" : "operational";
                String message = "degraded".equals(status) ? "slow response: " + latencyMs + "ms" : "";
                return new ChannelMonitorCheckResult(model, status, latencyMs, pingLatencyMs, message, checkedAt);
            } catch (Exception ex) {
                int latencyMs = (int) Duration.between(startedAt, Instant.now()).toMillis();
                if (shouldRetry(ex) && attempt < MAX_TRANSIENT_RETRIES) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                return new ChannelMonitorCheckResult(model, "error", latencyMs, pingLatencyMs,
                        truncate(sanitizeErrorMessage(ex.getMessage() == null ? "request failed" : ex.getMessage())), checkedAt);
            }
        }
        log.warn("channel monitor exhausted retries unexpectedly for monitor={} model={}", record.id(), model);
        return new ChannelMonitorCheckResult(model, "error", null, pingLatencyMs, "request retries exhausted", checkedAt);
    }

    private ProviderRequest buildProviderRequest(ChannelMonitorRecord record, String apiKey, String model) throws IOException {
        String provider = record.provider();
        MonitorChallenge challenge = generateChallenge();
        String prompt = challenge.prompt();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        String url;
        String textPath;
        switch (provider) {
            case "openai" -> {
                url = record.endpoint() + "/v1/chat/completions";
                body.put("model", model);
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                body.put("max_tokens", 50);
                body.put("stream", false);
                headers.put("Authorization", "Bearer " + apiKey);
                textPath = "choices.0.message.content";
            }
            case "anthropic" -> {
                url = record.endpoint() + "/v1/messages";
                body.put("model", model);
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                body.put("max_tokens", 50);
                headers.put("x-api-key", apiKey);
                headers.put("anthropic-version", "2023-06-01");
                textPath = "content.0.text";
            }
            case "gemini" -> {
                url = record.endpoint() + "/v1beta/models/" + model + ":generateContent";
                body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
                body.put("generationConfig", Map.of("maxOutputTokens", 50));
                headers.put("x-goog-api-key", apiKey);
                textPath = "candidates.0.content.parts.0.text";
            }
            default -> throw new HttpStatusException(400, "provider must be one of openai/anthropic/gemini");
        }
        Map<String, Object> mergedBody = applyBodyOverride(body, record.bodyOverrideMode(), record.bodyOverride(), provider);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mergedBody)));
        Map<String, String> mergedHeaders = new LinkedHashMap<>(headers);
        if (record.extraHeaders() != null) {
            record.extraHeaders().forEach((key, value) -> {
                if (!isForbiddenHeaderName(key)) {
                    mergedHeaders.put(key, value);
                }
            });
        }
        mergedHeaders.forEach(builder::header);
        boolean replaceMode = "replace".equalsIgnoreCase(record.bodyOverrideMode());
        return new ProviderRequest(builder.build(), textPath, challenge.expectedAnswer(), replaceMode);
    }

    private Map<String, Object> applyBodyOverride(
            Map<String, Object> baseBody,
            String mode,
            Map<String, Object> override,
            String provider
    ) {
        if ("replace".equals(mode)) {
            return override == null ? Map.of() : new LinkedHashMap<>(override);
        }
        if (!"merge".equals(mode) || override == null || override.isEmpty()) {
            return baseBody;
        }
        Set<String> denyKeys = switch (provider) {
            case "openai" -> Set.of("model", "messages", "stream");
            case "anthropic" -> Set.of("model", "messages");
            case "gemini" -> Set.of("contents");
            default -> Set.of();
        };
        Map<String, Object> merged = new LinkedHashMap<>(baseBody);
        override.forEach((key, value) -> {
            if (!denyKeys.contains(key)) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private String extractText(String body, String textPath) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode current = root;
        for (String segment : textPath.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            if (segment.matches("\\d+")) {
                current = current.path(Integer.parseInt(segment));
            } else {
                current = current.path(segment);
            }
        }
        return current == null || current.isMissingNode() || current.isNull() ? null : current.asText();
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private boolean shouldRetry(Exception ex) {
        return ex instanceof IOException || ex instanceof InterruptedException;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            long jitterMs = ThreadLocalRandom.current().nextLong(50L, 151L);
            Thread.sleep(RETRY_BACKOFF_BASE.multipliedBy(attempt + 1L).toMillis() + jitterMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildUpstreamErrorMessage(int statusCode, String body) {
        String detail = extractErrorMessage(body);
        if (detail.isBlank()) {
            return truncate("upstream HTTP " + statusCode);
        }
        return truncate("upstream HTTP " + statusCode + ": " + detail);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            List<String> candidates = List.of(
                    root.path("error").path("message").asText(),
                    root.path("message").asText(),
                    root.path("detail").asText(),
                    root.path("error").asText(),
                    root.path("msg").asText()
            );
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) {
                    return sanitizeErrorMessage(candidate);
                }
            }
        } catch (Exception ignored) {
            // Fall back to raw body below.
        }
        return sanitizeErrorMessage(body);
    }

    private void validateWrite(
            String provider,
            String endpoint,
            String primaryModel,
            int intervalSeconds,
            String apiKeyPlaintext,
            String bodyMode,
            Map<String, Object> bodyOverride
    ) {
        normalizeProvider(provider);
        normalizeEndpoint(endpoint);
        requirePrimaryModel(primaryModel);
        requireInterval(intervalSeconds);
        validateBodyMode(bodyMode, bodyOverride);
        if (apiKeyPlaintext != null && apiKeyPlaintext.trim().isEmpty()) {
            throw new HttpStatusException(400, "api_key is required when creating a monitor");
        }
    }

    private String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new HttpStatusException(400, "name is required");
        }
        return name.trim();
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            throw new HttpStatusException(400, "provider must be one of openai/anthropic/gemini");
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROVIDERS.contains(normalized)) {
            throw new HttpStatusException(400, "provider must be one of openai/anthropic/gemini");
        }
        return normalized;
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new HttpStatusException(400, "endpoint must be a valid https URL");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException ex) {
            throw new HttpStatusException(400, "endpoint must be a valid https URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new HttpStatusException(400, "endpoint must use https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new HttpStatusException(400, "endpoint must be a valid https URL");
        }
        boolean hasPath = uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath());
        if (hasPath || uri.getQuery() != null || uri.getFragment() != null) {
            throw new HttpStatusException(400, "endpoint must be base origin only (no path/query/fragment)");
        }
        ensurePublicHost(uri.getHost(), false);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getAuthority();
    }

    private String requirePrimaryModel(String primaryModel) {
        if (primaryModel == null || primaryModel.trim().isEmpty()) {
            throw new HttpStatusException(400, "primary_model is required");
        }
        return primaryModel.trim();
    }

    private int requireInterval(Integer intervalSeconds) {
        if (intervalSeconds == null || intervalSeconds < 15 || intervalSeconds > 3600) {
            throw new HttpStatusException(400, "interval_seconds must be in [15, 3600]");
        }
        return intervalSeconds;
    }

    private List<String> normalizeModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String model : models) {
            if (model != null && !model.trim().isEmpty()) {
                out.add(model.trim());
            }
        }
        return List.copyOf(out);
    }

    private String normalizeGroupName(String groupName) {
        return groupName == null ? "" : groupName.trim();
    }

    private Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.trim();
            if (!HEADER_NAME_PATTERN.matcher(normalizedKey).matches()) {
                throw new HttpStatusException(400, "header name contains invalid characters");
            }
            if (isForbiddenHeaderName(normalizedKey)) {
                throw new HttpStatusException(400, "header name is forbidden (hop-by-hop or computed by HTTP client)");
            }
            out.put(normalizedKey, value == null ? "" : value);
        });
        return out;
    }

    private String resolveBodyMode(String bodyMode) {
        String normalized = bodyMode == null || bodyMode.trim().isEmpty() ? "off" : bodyMode.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_BODY_MODES.contains(normalized)) {
            throw new HttpStatusException(400, "body_override_mode must be one of off/merge/replace");
        }
        return normalized;
    }

    private Map<String, Object> normalizeBody(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(body);
    }

    private void validateBodyMode(String bodyMode, Map<String, Object> bodyOverride) {
        if (("merge".equals(bodyMode) || "replace".equals(bodyMode))
                && (bodyOverride == null || bodyOverride.isEmpty())) {
            throw new HttpStatusException(400, "body_override is required when body_override_mode is merge or replace");
        }
    }

    private boolean isForbiddenHeaderName(String name) {
        return FORBIDDEN_HEADER_NAMES.contains(name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    private void ensurePublicEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            ensurePublicHost(uri.getHost(), true);
        } catch (IllegalArgumentException ex) {
            throw new HttpStatusException(400, "endpoint must be a valid https URL");
        }
    }

    private void ensurePublicHost(String host, boolean runtimeCheck) {
        if (host == null || host.isBlank()) {
            throw new HttpStatusException(400, "endpoint must be a valid https URL");
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTNAMES.contains(normalized)) {
            throw new HttpStatusException(400, "endpoint must be a public host");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new HttpStatusException(400, runtimeCheck
                        ? "endpoint hostname could not be resolved"
                        : "endpoint hostname could not be resolved");
            }
            for (InetAddress address : addresses) {
                if (isPrivateAddress(address)) {
                    throw new HttpStatusException(400, "endpoint must be a public host");
                }
            }
        } catch (UnknownHostException ex) {
            throw new HttpStatusException(400, "endpoint hostname could not be resolved");
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if (first == 0 || first == 127) {
                return true;
            }
            return first == 100 && second >= 64 && second <= 127;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
            return first == 0xFE && (second & 0xC0) == 0x80;
        }
        return false;
    }

    private MonitorChallenge generateChallenge() {
        int a = ThreadLocalRandom.current().nextInt(1, 51);
        int b = ThreadLocalRandom.current().nextInt(1, 51);
        if (ThreadLocalRandom.current().nextBoolean()) {
            return new MonitorChallenge(String.format(CHALLENGE_PROMPT_TEMPLATE, a, "+", b), String.valueOf(a + b));
        }
        int hi = Math.max(a, b);
        int lo = Math.min(a, b);
        return new MonitorChallenge(String.format(CHALLENGE_PROMPT_TEMPLATE, hi, "-", lo), String.valueOf(hi - lo));
    }

    private boolean validateChallenge(String responseText, String expectedAnswer) {
        if (responseText == null || responseText.isBlank() || expectedAnswer == null || expectedAnswer.isBlank()) {
            return false;
        }
        java.util.regex.Matcher matcher = CHALLENGE_NUMBER_PATTERN.matcher(responseText);
        while (matcher.find()) {
            if (expectedAnswer.equals(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String sanitized = SENSITIVE_QUERY_PARAM_PATTERN.matcher(message).replaceAll("$1REDACTED");
        sanitized = ANTHROPIC_KEY_PATTERN.matcher(sanitized).replaceAll("sk-ant-***REDACTED***");
        sanitized = OPENAI_KEY_PATTERN.matcher(sanitized).replaceAll("sk-***REDACTED***");
        sanitized = GEMINI_KEY_PATTERN.matcher(sanitized).replaceAll("AIza***REDACTED***");
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("eyJ***REDACTED.JWT***");
        return sanitized;
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        String normalized = sanitizeErrorMessage(message).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 485) + "...(truncated)";
    }

    private record ProviderRequest(
            HttpRequest request,
            String textPath,
            String expectedAnswer,
            boolean skipChallengeValidation
    ) {
    }

    private record MonitorChallenge(String prompt, String expectedAnswer) {
    }
}
