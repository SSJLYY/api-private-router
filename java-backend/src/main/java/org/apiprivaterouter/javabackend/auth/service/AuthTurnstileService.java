package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Service
public class AuthTurnstileService {

    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final AdminSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AuthTurnstileService(
            AdminSettingsRepository settingsRepository,
            ObjectMapper objectMapper
    ) {
        this.settingsRepository = settingsRepository;
        this.objectMapper = objectMapper;
    }

    public void verify(String token, String remoteIp) {
        if (!isEnabled()) {
            return;
        }
        String secretKey = trimToNull(settingsRepository.getSettingValue("turnstile_secret_key"));
        if (secretKey == null) {
            throw new ApiErrorException(503, "TURNSTILE_NOT_CONFIGURED", "turnstile not configured");
        }
        if (trimToNull(token) == null) {
            throw new ApiErrorException(400, "TURNSTILE_VERIFICATION_FAILED", "turnstile verification failed");
        }

        StringBuilder body = new StringBuilder()
                .append("secret=").append(urlEncode(secretKey))
                .append("&response=").append(urlEncode(token.trim()));
        String normalizedRemoteIp = trimToNull(remoteIp);
        if (normalizedRemoteIp != null) {
            body.append("&remoteip=").append(urlEncode(normalizedRemoteIp));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(VERIFY_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            TurnstileVerifyResponse payload = objectMapper.readValue(response.body(), TurnstileVerifyResponse.class);
            if (payload == null) {
                throw new ApiErrorException(400, "TURNSTILE_VERIFICATION_FAILED", "turnstile verification failed");
            }
            if (payload.errorCodes() != null && payload.errorCodes().contains("invalid-input-secret")) {
                throw new ApiErrorException(400, "TURNSTILE_INVALID_SECRET_KEY", "invalid turnstile secret key");
            }
            if (!payload.success()) {
                throw new ApiErrorException(400, "TURNSTILE_VERIFICATION_FAILED", "turnstile verification failed");
            }
        } catch (ApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiErrorException(503, "TURNSTILE_VERIFICATION_FAILED", "turnstile verification failed");
        }
    }

    private boolean isEnabled() {
        return "true".equalsIgnoreCase(trimToNull(settingsRepository.getSettingValue("turnstile_enabled")));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record TurnstileVerifyResponse(
            boolean success,
            @JsonProperty("error-codes") List<String> errorCodes
    ) {
    }
}
