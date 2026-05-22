package org.apiprivaterouter.javabackend.admin.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.RefreshTierResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class GeminiTierRefreshService {

    private static final long GB = 1024L * 1024L * 1024L;
    private static final long TB = 1024L * GB;
    private static final long STORAGE_TIER_UNLIMITED = 100L * TB;
    private static final long STORAGE_TIER_AI_PREMIUM = 2L * TB;
    private static final long STORAGE_TIER_FREE = 15L * GB;

    private final ObjectMapper objectMapper;

    public GeminiTierRefreshService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RefreshTierComputation refreshGoogleOneTier(AdminAccountResponse account, AdminProxyResponse proxy) {
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        if (!"gemini".equals(account.platform()) || !"oauth".equals(account.type())) {
            throw new IllegalArgumentException("Only Gemini OAuth accounts support tier refresh");
        }

        Map<String, Object> credentials = account.credentials() == null
                ? Map.of()
                : account.credentials();
        String oauthType = stringValue(credentials.get("oauth_type"));
        if (!"google_one".equals(oauthType)) {
            throw new IllegalArgumentException("Only google_one OAuth accounts support tier refresh");
        }

        String accessToken = stringValue(credentials.get("access_token"));
        if (accessToken == null) {
            throw new IllegalArgumentException("missing access_token");
        }

        DriveStorageInfo storageInfo = fetchStorageQuota(accessToken, proxy);
        String tierId = inferGoogleOneTier(storageInfo.limit());
        String updatedAt = Instant.now().toString();

        Map<String, Object> mergedCredentials = new LinkedHashMap<>(credentials);
        mergedCredentials.put("tier_id", tierId);

        Map<String, Object> mergedExtra = new LinkedHashMap<>(account.extra() == null ? Map.of() : account.extra());
        mergedExtra.put("drive_storage_limit", storageInfo.limit());
        mergedExtra.put("drive_storage_usage", storageInfo.usage());
        mergedExtra.put("drive_tier_updated_at", updatedAt);

        Map<String, Object> storagePayload = new LinkedHashMap<>();
        storagePayload.put("drive_storage_limit", storageInfo.limit());
        storagePayload.put("drive_storage_usage", storageInfo.usage());
        storagePayload.put("drive_tier_updated_at", updatedAt);

        return new RefreshTierComputation(
                tierId,
                mergedCredentials,
                mergedExtra,
                new RefreshTierResponse(
                        tierId,
                        storagePayload,
                        storageInfo.limit(),
                        storageInfo.usage(),
                        updatedAt
                )
        );
    }

    private DriveStorageInfo fetchStorageQuota(String accessToken, AdminProxyResponse proxy) {
        HttpClient client = buildHttpClient(proxy);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/drive/v3/about?fields=storageQuota"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        IOException lastIo = null;
        InterruptedException lastInterrupted = null;
        int[] retryStatuses = {429, 500, 502, 503};
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    return parseStorageQuota(response.body());
                }
                boolean retryable = false;
                for (int retryStatus : retryStatuses) {
                    if (status == retryStatus) {
                        retryable = true;
                        break;
                    }
                }
                if (retryable && attempt < 2) {
                    sleepBackoff(attempt);
                    continue;
                }
                throw new IllegalArgumentException("drive API error: status " + status);
            } catch (IOException ex) {
                lastIo = ex;
                if (attempt < 2) {
                    sleepBackoff(attempt);
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastInterrupted = ex;
                break;
            }
        }

        if (lastInterrupted != null) {
            throw new IllegalArgumentException("request interrupted");
        }
        throw new IllegalArgumentException("failed to fetch Drive storage quota: " + (lastIo == null ? "unknown error" : lastIo.getMessage()));
    }

    private DriveStorageInfo parseStorageQuota(String body) {
        if (body == null || body.isBlank()) {
            return new DriveStorageInfo(0L, 0L);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode quota = root.path("storageQuota");
            return new DriveStorageInfo(
                    parseLong(quota.path("limit").asText(null)) == null ? 0L : parseLong(quota.path("limit").asText(null)),
                    parseLong(quota.path("usage").asText(null)) == null ? 0L : parseLong(quota.path("usage").asText(null))
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to decode Drive API response");
        }
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private HttpClient buildHttpClient(AdminProxyResponse proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);

        if (proxy == null || proxy.host() == null || proxy.host().isBlank() || proxy.port() <= 0) {
            return builder.build();
        }

        String protocol = proxy.protocol() == null ? "" : proxy.protocol().trim().toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https") && !protocol.equals("socks5") && !protocol.equals("socks") && !protocol.equals("socks5h")) {
            return builder.build();
        }

        builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
        if (proxy.username() != null && !proxy.username().isBlank()) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(proxy.username(), (proxy.password() == null ? "" : proxy.password()).toCharArray());
                }
            });
        }
        return builder.build();
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep((1L << attempt) * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("request interrupted");
        }
    }

    private String inferGoogleOneTier(long storageBytes) {
        if (storageBytes <= 0) {
            return "google_one_unknown";
        }
        if (storageBytes > STORAGE_TIER_UNLIMITED) {
            return "google_ai_ultra";
        }
        if (storageBytes >= STORAGE_TIER_AI_PREMIUM) {
            return "google_ai_pro";
        }
        if (storageBytes >= STORAGE_TIER_FREE) {
            return "google_one_free";
        }
        return "google_one_unknown";
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record DriveStorageInfo(long limit, long usage) {
    }

    public record RefreshTierComputation(
            String tierId,
            Map<String, Object> credentials,
            Map<String, Object> extra,
            RefreshTierResponse response
    ) {
    }
}
