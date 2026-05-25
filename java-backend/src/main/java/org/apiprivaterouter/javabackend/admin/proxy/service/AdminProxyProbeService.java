package org.apiprivaterouter.javabackend.admin.proxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyQualityCheckItemResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyQualityCheckResultResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.TestProxyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdminProxyProbeService {

    private static final Duration EXIT_PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration QUALITY_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int EXIT_PROBE_MAX_BODY_BYTES = 1024 * 1024;
    private static final int QUALITY_MAX_BODY_BYTES = 8 * 1024;
    private static final String QUALITY_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
    private static final Pattern CF_RAY_PATTERN = Pattern.compile("(?i)cf-ray[:\\s=]+([a-z0-9-]+)");
    private static final Pattern C_RAY_PATTERN = Pattern.compile("(?i)cRay:\\s*'([a-z0-9-]+)'");
    private static final List<String> CLOUDFLARE_HTML_MARKERS = List.of(
            "window._cf_chl_opt",
            "just a moment",
            "enable javascript and cookies to continue",
            "__cf_chl_",
            "challenge-platform"
    );
    private static final List<ExitProbeTarget> DEFAULT_EXIT_PROBE_TARGETS = List.of(
            new ExitProbeTarget("http://ip-api.com/json/?lang=zh-CN", "ip-api"),
            new ExitProbeTarget("http://httpbin.org/ip", "httpbin")
    );
    private static final List<QualityTarget> DEFAULT_QUALITY_TARGETS = List.of(
            new QualityTarget("openai", "https://api.openai.com/v1/models", "GET", Set.of(401)),
            new QualityTarget("anthropic", "https://api.anthropic.com/v1/messages", "GET", Set.of(401, 405, 404, 400)),
            new QualityTarget("gemini", "https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta", "GET", Set.of(200))
    );

    private final ObjectMapper objectMapper;
    private final HttpClientFactory httpClientFactory;
    private final List<ExitProbeTarget> exitProbeTargets;
    private final List<QualityTarget> qualityTargets;

    @Autowired
    public AdminProxyProbeService(ObjectMapper objectMapper) {
        this(objectMapper, new DefaultHttpClientFactory(), DEFAULT_EXIT_PROBE_TARGETS, DEFAULT_QUALITY_TARGETS);
    }

    AdminProxyProbeService(
            ObjectMapper objectMapper,
            HttpClientFactory httpClientFactory,
            List<ExitProbeTarget> exitProbeTargets,
            List<QualityTarget> qualityTargets
    ) {
        this.objectMapper = objectMapper;
        this.httpClientFactory = httpClientFactory;
        this.exitProbeTargets = List.copyOf(exitProbeTargets);
        this.qualityTargets = List.copyOf(qualityTargets);
    }

    public TestProxyResponse testProxy(AdminProxyResponse proxy) {
        try {
            ExitProbeResult result = probeExit(proxy);
            return new TestProxyResponse(
                    true,
                    "Proxy is accessible",
                    result.latencyMs(),
                    result.ipAddress(),
                    result.city(),
                    result.region(),
                    result.country(),
                    result.countryCode()
            );
        } catch (ProbeFailureException ex) {
            return new TestProxyResponse(
                    false,
                    ex.getMessage(),
                    ex.latencyMs(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    public ProxyQualityCheckResultResponse checkProxyQuality(long proxyId, AdminProxyResponse proxy) {
        long checkedAt = Instant.now().getEpochSecond();
        List<ProxyQualityCheckItemResponse> items = new ArrayList<>();
        int passedCount = 0;
        int warnCount = 0;
        int failedCount = 0;
        int challengeCount = 0;
        String exitIp = null;
        String country = null;
        String countryCode = null;
        Long baseLatencyMs = null;

        ExitProbeResult exitResult;
        try {
            exitResult = probeExit(proxy);
        } catch (ProbeFailureException ex) {
            items.add(new ProxyQualityCheckItemResponse(
                    "base_connectivity",
                    "fail",
                    null,
                    ex.latencyMs(),
                    ex.getMessage(),
                    null
            ));
            failedCount++;
            return finalizeResult(
                    proxyId,
                    checkedAt,
                    exitIp,
                    country,
                    countryCode,
                    baseLatencyMs,
                    passedCount,
                    warnCount,
                    failedCount,
                    challengeCount,
                    items
            );
        }

        exitIp = exitResult.ipAddress();
        country = exitResult.country();
        countryCode = exitResult.countryCode();
        baseLatencyMs = exitResult.latencyMs();
        items.add(new ProxyQualityCheckItemResponse(
                "base_connectivity",
                "pass",
                null,
                exitResult.latencyMs(),
                "Proxy exit connectivity is healthy",
                null
        ));
        passedCount++;

        HttpClient client;
        try {
            client = httpClientFactory.create(proxy, QUALITY_REQUEST_TIMEOUT);
        } catch (RuntimeException ex) {
            items.add(new ProxyQualityCheckItemResponse(
                    "http_client",
                    "fail",
                    null,
                    null,
                    "Failed to create probe client: " + defaultMessage(ex),
                    null
            ));
            failedCount++;
            return finalizeResult(
                    proxyId,
                    checkedAt,
                    exitIp,
                    country,
                    countryCode,
                    baseLatencyMs,
                    passedCount,
                    warnCount,
                    failedCount,
                    challengeCount,
                    items
            );
        }

        for (QualityTarget target : qualityTargets) {
            ProxyQualityCheckItemResponse item = runQualityTarget(client, target);
            items.add(item);
            switch (item.status()) {
                case "pass" -> passedCount++;
                case "warn" -> warnCount++;
                case "challenge" -> challengeCount++;
                default -> failedCount++;
            }
        }

        return finalizeResult(
                proxyId,
                checkedAt,
                exitIp,
                country,
                countryCode,
                baseLatencyMs,
                passedCount,
                warnCount,
                failedCount,
                challengeCount,
                items
        );
    }

    private ProxyQualityCheckResultResponse finalizeResult(
            long proxyId,
            long checkedAt,
            String exitIp,
            String country,
            String countryCode,
            Long baseLatencyMs,
            int passedCount,
            int warnCount,
            int failedCount,
            int challengeCount,
            List<ProxyQualityCheckItemResponse> items
    ) {
        int score = 100 - warnCount * 10 - failedCount * 22 - challengeCount * 30;
        if (score < 0) {
            score = 0;
        }
        return new ProxyQualityCheckResultResponse(
                proxyId,
                score,
                qualityGrade(score),
                "Passed " + passedCount + ", warned " + warnCount + ", failed " + failedCount + ", challenged " + challengeCount,
                blankToNull(exitIp),
                blankToNull(country),
                blankToNull(countryCode),
                baseLatencyMs,
                passedCount,
                warnCount,
                failedCount,
                challengeCount,
                checkedAt,
                List.copyOf(items)
        );
    }

    private String qualityGrade(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 75) {
            return "B";
        }
        if (score >= 60) {
            return "C";
        }
        if (score >= 40) {
            return "D";
        }
        return "F";
    }

    private ExitProbeResult probeExit(AdminProxyResponse proxy) throws ProbeFailureException {
        HttpClient client;
        try {
            client = httpClientFactory.create(proxy, EXIT_PROBE_TIMEOUT);
        } catch (RuntimeException ex) {
            throw new ProbeFailureException("failed to create proxy client: " + defaultMessage(ex), null);
        }

        ProbeFailureException lastFailure = null;
        for (ExitProbeTarget target : exitProbeTargets) {
            try {
                return runExitProbe(client, target);
            } catch (ProbeFailureException ex) {
                lastFailure = ex;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new ProbeFailureException("no exit probe targets configured", null);
    }

    private ExitProbeResult runExitProbe(HttpClient client, ExitProbeTarget target) throws ProbeFailureException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(target.url()))
                .timeout(EXIT_PROBE_TIMEOUT)
                .GET()
                .build();
        long startedAt = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long latencyMs = elapsedMillis(startedAt);
            try (InputStream inputStream = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ProbeFailureException("request failed with status: " + response.statusCode(), latencyMs);
                }
                byte[] body = readStrictBody(inputStream, EXIT_PROBE_MAX_BODY_BYTES);
                return switch (target.parser()) {
                    case "ip-api" -> parseIpApi(body, latencyMs);
                    case "httpbin" -> parseHttpBin(body, latencyMs);
                    default -> throw new ProbeFailureException("unknown exit probe parser: " + target.parser(), latencyMs);
                };
            }
        } catch (IOException ex) {
            throw new ProbeFailureException("proxy connection failed: " + defaultMessage(ex), elapsedMillis(startedAt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProbeFailureException("proxy connection interrupted", elapsedMillis(startedAt));
        }
    }

    private ExitProbeResult parseIpApi(byte[] body, long latencyMs) throws ProbeFailureException {
        try {
            JsonNode root = objectMapper.readTree(body);
            String status = trimToNull(root.path("status").asText(null));
            if (!"success".equalsIgnoreCase(status)) {
                String message = trimToNull(root.path("message").asText(null));
                throw new ProbeFailureException("ip-api request failed: " + (message == null ? "unknown error" : message), latencyMs);
            }
            String region = trimToNull(root.path("regionName").asText(null));
            if (region == null) {
                region = trimToNull(root.path("region").asText(null));
            }
            return new ExitProbeResult(
                    latencyMs,
                    trimToNull(root.path("query").asText(null)),
                    trimToNull(root.path("city").asText(null)),
                    region,
                    trimToNull(root.path("country").asText(null)),
                    trimToNull(root.path("countryCode").asText(null))
            );
        } catch (IOException ex) {
            throw new ProbeFailureException("failed to parse ip-api response: " + previewBody(body), latencyMs);
        }
    }

    private ExitProbeResult parseHttpBin(byte[] body, long latencyMs) throws ProbeFailureException {
        try {
            JsonNode root = objectMapper.readTree(body);
            String origin = trimToNull(root.path("origin").asText(null));
            if (origin == null) {
                throw new ProbeFailureException("httpbin: no IP found in response", latencyMs);
            }
            return new ExitProbeResult(latencyMs, origin, null, null, null, null);
        } catch (IOException ex) {
            throw new ProbeFailureException("failed to parse httpbin response: " + previewBody(body), latencyMs);
        }
    }

    private ProxyQualityCheckItemResponse runQualityTarget(HttpClient client, QualityTarget target) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(target.url()))
                    .timeout(QUALITY_REQUEST_TIMEOUT)
                    .header("Accept", "application/json,text/html,*/*")
                    .header("User-Agent", QUALITY_USER_AGENT)
                    .method(target.method(), HttpRequest.BodyPublishers.noBody())
                    .build();
        } catch (IllegalArgumentException ex) {
            return new ProxyQualityCheckItemResponse(target.target(), "fail", null, null, "Failed to build request: " + defaultMessage(ex), null);
        }

        long startedAt = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long latencyMs = elapsedMillis(startedAt);
            try (InputStream inputStream = response.body()) {
                byte[] body = readTruncatedBody(inputStream, QUALITY_MAX_BODY_BYTES);
                String cfRay = extractCloudflareRayId(response, body);
                if (isCloudflareChallenge(response, body)) {
                    return new ProxyQualityCheckItemResponse(
                            target.target(),
                            "challenge",
                            response.statusCode(),
                            latencyMs,
                            "Cloudflare challenge encountered",
                            cfRay
                    );
                }

                if (target.allowedStatuses().contains(response.statusCode())) {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return new ProxyQualityCheckItemResponse(target.target(), "pass", response.statusCode(), latencyMs, "HTTP " + response.statusCode(), cfRay);
                    }
                    return new ProxyQualityCheckItemResponse(target.target(), "warn", response.statusCode(), latencyMs, "HTTP " + response.statusCode() + " indicates the target is reachable but restricted", cfRay);
                }

                if (response.statusCode() == 429) {
                    return new ProxyQualityCheckItemResponse(target.target(), "warn", response.statusCode(), latencyMs, "HTTP 429 indicates possible rate limiting", cfRay);
                }

                return new ProxyQualityCheckItemResponse(target.target(), "fail", response.statusCode(), latencyMs, "Unexpected HTTP status: " + response.statusCode(), cfRay);
            }
        } catch (IOException ex) {
            return new ProxyQualityCheckItemResponse(
                    target.target(),
                    "fail",
                    null,
                    elapsedMillis(startedAt),
                    "Request failed: " + defaultMessage(ex),
                    null
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProxyQualityCheckItemResponse(
                    target.target(),
                    "fail",
                    null,
                    elapsedMillis(startedAt),
                    "Request interrupted",
                    null
            );
        }
    }

    private boolean isCloudflareChallenge(HttpResponse<?> response, byte[] body) {
        int statusCode = response.statusCode();
        if (statusCode != 403 && statusCode != 429) {
            return false;
        }
        String mitigated = trimToNull(response.headers().firstValue("cf-mitigated").orElse(null));
        if ("challenge".equalsIgnoreCase(mitigated)) {
            return true;
        }
        String preview = truncateBody(body, 4096).toLowerCase(Locale.ROOT);
        for (String marker : CLOUDFLARE_HTML_MARKERS) {
            if (preview.contains(marker)) {
                return true;
            }
        }
        String contentType = response.headers()
                .firstValue("content-type")
                .map(value -> value.toLowerCase(Locale.ROOT).trim())
                .orElse("");
        return contentType.contains("text/html")
                && (preview.contains("<html") || preview.contains("<!doctype html"))
                && (preview.contains("cloudflare") || preview.contains("challenge"));
    }

    private String extractCloudflareRayId(HttpResponse<?> response, byte[] body) {
        String headerValue = trimToNull(response.headers().firstValue("cf-ray").orElse(null));
        if (headerValue == null) {
            headerValue = trimToNull(response.headers().firstValue("Cf-Ray").orElse(null));
        }
        if (headerValue != null) {
            return headerValue;
        }
        String preview = truncateBody(body, 8192);
        Matcher cfRayMatcher = CF_RAY_PATTERN.matcher(preview);
        if (cfRayMatcher.find()) {
            return trimToNull(cfRayMatcher.group(1));
        }
        Matcher cRayMatcher = C_RAY_PATTERN.matcher(preview);
        if (cRayMatcher.find()) {
            return trimToNull(cRayMatcher.group(1));
        }
        return null;
    }

    private byte[] readStrictBody(InputStream inputStream, int maxBytes) throws IOException, ProbeFailureException {
        byte[] body = inputStream.readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new ProbeFailureException("proxy probe response exceeds limit: " + maxBytes, null);
        }
        return body;
    }

    private byte[] readTruncatedBody(InputStream inputStream, int maxBytes) throws IOException {
        byte[] body = inputStream.readNBytes(maxBytes + 1);
        if (body.length <= maxBytes) {
            return body;
        }
        return Arrays.copyOf(body, maxBytes);
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String previewBody(byte[] body) {
        String preview = truncateBody(body, 200);
        return preview.isBlank() ? "<empty>" : preview;
    }

    private String truncateBody(byte[] body, int maxLength) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToNull(String value) {
        return trimToNull(value);
    }

    private String defaultMessage(Throwable throwable) {
        String message = trimToNull(throwable == null ? null : throwable.getMessage());
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    interface HttpClientFactory {
        HttpClient create(AdminProxyResponse proxy, Duration connectTimeout);
    }

    private static final class DefaultHttpClientFactory implements HttpClientFactory {

        @Override
        public HttpClient create(AdminProxyResponse proxy, Duration connectTimeout) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER);
            if (proxy == null || isBlank(proxy.host()) || proxy.port() <= 0) {
                return builder.build();
            }
            Proxy.Type proxyType = proxy.protocol() != null && proxy.protocol().toLowerCase(Locale.ROOT).startsWith("socks")
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            builder.proxy(new FixedProxySelector(proxyType, proxy.host(), proxy.port()));
            if (!isBlank(proxy.username())) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                proxy.username(),
                                (proxy.password() == null ? "" : proxy.password()).toCharArray()
                        );
                    }
                });
            }
            return builder.build();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    private static final class FixedProxySelector extends ProxySelector {

        private final List<Proxy> proxies;

        private FixedProxySelector(Proxy.Type type, String host, int port) {
            this.proxies = List.of(new Proxy(type, new InetSocketAddress(host, port)));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return proxies;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }

    record ExitProbeTarget(
            String url,
            String parser
    ) {
    }

    record QualityTarget(
            String target,
            String url,
            String method,
            Set<Integer> allowedStatuses
    ) {
    }

    private record ExitProbeResult(
            Long latencyMs,
            String ipAddress,
            String city,
            String region,
            String country,
            String countryCode
    ) {
    }

    private static final class ProbeFailureException extends Exception {

        private final Long latencyMs;

        private ProbeFailureException(String message, Long latencyMs) {
            super(message);
            this.latencyMs = latencyMs;
        }

        private Long latencyMs() {
            return latencyMs;
        }
    }
}
