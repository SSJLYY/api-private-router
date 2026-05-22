package org.apiprivaterouter.javabackend.admin.account.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AccountPrivacyService {

    private static final String OPENAI_SETTINGS_URL = "https://chatgpt.com/backend-api/settings/account_user_setting?feature=training_allowed&value=false";
    private static final String ANTIGRAVITY_BASE_URL = "https://daily-cloudcode-pa.googleapis.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;

    public AccountPrivacyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String forcePrivacy(AdminAccountResponse account, AdminProxyResponse proxy) {
        if (account == null) {
            throw new IllegalArgumentException("account not found");
        }
        if (!"oauth".equals(account.type())) {
            throw new IllegalArgumentException("Only OAuth accounts support privacy setting");
        }
        return switch (account.platform()) {
            case "openai" -> forceOpenAiPrivacy(account, proxy);
            case "antigravity" -> forceAntigravityPrivacy(account, proxy);
            default -> throw new IllegalArgumentException("Only OpenAI and Antigravity OAuth accounts support privacy setting");
        };
    }

    public Map<String, Object> mergePrivacyMode(Map<String, Object> currentExtra, String mode) {
        Map<String, Object> extra = new LinkedHashMap<>(currentExtra == null ? Map.of() : currentExtra);
        extra.put("privacy_mode", mode);
        return extra;
    }

    private String forceOpenAiPrivacy(AdminAccountResponse account, AdminProxyResponse proxy) {
        String accessToken = stringValue(account.credentials() == null ? null : account.credentials().get("access_token"));
        if (accessToken == null) {
            return "";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_SETTINGS_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Origin", "https://chatgpt.com")
                .header("Referer", "https://chatgpt.com/")
                .header("Accept", "application/json")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .header("sec-fetch-dest", "empty")
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> response = buildHttpClient(proxy).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status == 403 || status == 503) {
                String lower = body.toLowerCase(Locale.ROOT);
                if (lower.contains("cloudflare") || lower.contains("cf-") || body.contains("Just a moment")) {
                    return "training_set_cf_blocked";
                }
            }
            return status >= 200 && status < 300 ? "training_off" : "training_set_failed";
        } catch (IOException ex) {
            return "training_set_failed";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "training_set_failed";
        }
    }

    private String forceAntigravityPrivacy(AdminAccountResponse account, AdminProxyResponse proxy) {
        String accessToken = stringValue(account.credentials() == null ? null : account.credentials().get("access_token"));
        if (accessToken == null) {
            return "";
        }
        String projectId = stringValue(account.credentials() == null ? null : account.credentials().get("project_id"));
        if (projectId == null) {
            return "privacy_set_failed";
        }

        try {
            HttpClient client = buildHttpClient(proxy);
            SetUserSettingsResponse setResponse = postJson(
                    client,
                    ANTIGRAVITY_BASE_URL + "/v1internal:setUserSettings",
                    accessToken,
                    Map.of("user_settings", Map.of()),
                    SetUserSettingsResponse.class
            );
            if (!setResponse.isSuccess()) {
                return "privacy_set_failed";
            }

            FetchUserInfoResponse userInfo = postJson(
                    client,
                    ANTIGRAVITY_BASE_URL + "/v1internal:fetchUserInfo",
                    accessToken,
                    Map.of("project", projectId),
                    FetchUserInfoResponse.class
            );
            return userInfo.isPrivate() ? "privacy_set" : "privacy_set_failed";
        } catch (IOException ex) {
            return "privacy_set_failed";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "privacy_set_failed";
        }
    }

    private <T> T postJson(HttpClient client, String url, String accessToken, Object payload, Class<T> type)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("User-Agent", "google-api-nodejs-client/9.15.1")
                .header("X-Goog-Api-Client", "gl-node/22.21.1")
                .header("Host", "daily-cloudcode-pa.googleapis.com")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("privacy request failed: status " + response.statusCode() + ", body: " + response.body());
        }
        return objectMapper.readValue(response.body(), type);
    }

    private HttpClient buildHttpClient(AdminProxyResponse proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy == null || proxy.host() == null || proxy.host().isBlank() || proxy.port() <= 0) {
            return builder.build();
        }

        String protocol = proxy.protocol() == null ? "" : proxy.protocol().trim().toLowerCase(Locale.ROOT);
        Proxy.Type type = protocol.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        builder.proxy(new FixedProxySelector(type, proxy.host(), proxy.port()));
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

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SetUserSettingsResponse(
            @JsonProperty("userSettings") Map<String, Object> userSettings
    ) {
        private boolean isSuccess() {
            return userSettings == null || userSettings.isEmpty() || !userSettings.containsKey("telemetryEnabled");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FetchUserInfoResponse(
            @JsonProperty("userSettings") Map<String, Object> userSettings
    ) {
        private boolean isPrivate() {
            return userSettings == null || !userSettings.containsKey("telemetryEnabled");
        }
    }
}
