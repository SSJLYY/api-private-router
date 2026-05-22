package org.apiprivaterouter.javabackend.common.security;

import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpstreamUrlGuardTest {

    private final UpstreamUrlGuard guard = new UpstreamUrlGuard(
            new UrlAllowlistProperties(true, null, null, null, false, false)
    );

    @Test
    void normalizeOpenAiBaseUrlAllowsOfficialHost() {
        Map<String, Object> normalized = guard.normalizeAccountCredentials(
                "openai",
                "apikey",
                Map.of("base_url", "https://api.openai.com/v1/")
        );

        assertEquals("https://api.openai.com/v1", normalized.get("base_url"));
    }

    @Test
    void normalizeAccountCredentialsRejectsLoopbackHost() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> guard.normalizeAccountCredentials("openai", "apikey", Map.of("base_url", "https://127.0.0.1:8080"))
        );

        assertEquals("invalid base_url: host is not allowed", ex.getMessage());
    }

    @Test
    void normalizeAccountCredentialsRejectsHttpByDefault() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> guard.normalizeAccountCredentials("openai", "apikey", Map.of("base_url", "http://api.openai.com"))
        );

        assertEquals("invalid base_url: unsupported scheme", ex.getMessage());
    }

    @Test
    void normalizePublicApiBaseUrlAllowsRelativePath() {
        assertEquals("/api/v1", guard.normalizePublicApiBaseUrl("/api/v1/"));
    }
}
