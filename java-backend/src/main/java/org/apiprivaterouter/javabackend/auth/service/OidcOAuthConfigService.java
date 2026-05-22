package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.publicsettings.repository.PublicSettingsRepository;

import java.io.IOException;
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
public class OidcOAuthConfigService {

    private static final String DEFAULT_PROVIDER_NAME = "OIDC";
    private static final String DEFAULT_SCOPES = "openid email profile";
    private static final String DEFAULT_FRONTEND_REDIRECT = "/auth/oidc/callback";
    private static final String DEFAULT_TOKEN_AUTH_METHOD = "client_secret_post";
    private static final String DEFAULT_ALLOWED_SIGNING_ALGS = "RS256,ES256,PS256";
    private static final int DEFAULT_CLOCK_SKEW_SECONDS = 120;

    private final PublicSettingsRepository repository;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OidcOAuthConfigService(
            PublicSettingsRepository repository,
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

    public OidcOAuthConfig getRequiredConfig() {
        OidcOAuthConfig config = getEffectiveConfig();
        if (!config.enabled()) {
            throw new StructuredApiErrorException(404, "OAUTH_DISABLED", "oauth login is disabled");
        }
        if (config.clientId().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth client id not configured");
        }
        validateAbsoluteHttpUrl(config.authorizeUrl(), "authorize");
        validateAbsoluteHttpUrl(config.tokenUrl(), "token");
        if (!config.userInfoUrl().isBlank()) {
            validateAbsoluteHttpUrl(config.userInfoUrl(), "userinfo");
        }
        validateAbsoluteHttpUrl(config.redirectUrl(), "redirect");
        validateFrontendRedirectUrl(config.frontendRedirectUrl());
        String method = normalizeTokenAuthMethod(config.tokenAuthMethod());
        if (!"none".equals(method) && config.clientSecret().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth client secret not configured");
        }
        if (config.validateIdToken()) {
            validateAbsoluteHttpUrl(config.issuerUrl(), "issuer");
            validateAbsoluteHttpUrl(config.jwksUrl(), "jwks");
        }
        return new OidcOAuthConfig(
                config.enabled(),
                config.providerName(),
                config.clientId(),
                config.clientSecret(),
                config.issuerUrl(),
                config.discoveryUrl(),
                config.authorizeUrl(),
                config.tokenUrl(),
                config.userInfoUrl(),
                config.jwksUrl(),
                config.scopes(),
                config.redirectUrl(),
                config.frontendRedirectUrl(),
                method,
                config.usePkce(),
                config.validateIdToken(),
                config.allowedSigningAlgs(),
                config.clockSkewSeconds(),
                config.requireEmailVerified(),
                config.userInfoEmailPath(),
                config.userInfoIdPath(),
                config.userInfoUsernamePath()
        );
    }

    public OidcOAuthConfig getEffectiveConfig() {
        Map<String, String> settings = repository.getValues(List.of(
                "oidc_connect_enabled",
                "oidc_connect_provider_name",
                "oidc_connect_client_id",
                "oidc_connect_client_secret",
                "oidc_connect_issuer_url",
                "oidc_connect_discovery_url",
                "oidc_connect_authorize_url",
                "oidc_connect_token_url",
                "oidc_connect_userinfo_url",
                "oidc_connect_jwks_url",
                "oidc_connect_scopes",
                "oidc_connect_redirect_url",
                "oidc_connect_frontend_redirect_url",
                "oidc_connect_token_auth_method",
                "oidc_connect_use_pkce",
                "oidc_connect_validate_id_token",
                "oidc_connect_allowed_signing_algs",
                "oidc_connect_clock_skew_seconds",
                "oidc_connect_require_email_verified",
                "oidc_connect_userinfo_email_path",
                "oidc_connect_userinfo_id_path",
                "oidc_connect_userinfo_username_path"
        ));

        OidcOAuthConfig raw = new OidcOAuthConfig(
                resolveFlag(settings, "oidc_connect_enabled", false, "oidc_connect.enabled"),
                firstNonBlank(
                        settings.get("oidc_connect_provider_name"),
                        environment.getProperty("oidc_connect.provider_name"),
                        environment.getProperty("OIDC_CONNECT_PROVIDER_NAME"),
                        DEFAULT_PROVIDER_NAME
                ),
                firstNonBlank(
                        settings.get("oidc_connect_client_id"),
                        environment.getProperty("oidc_connect.client_id"),
                        environment.getProperty("OIDC_CONNECT_CLIENT_ID")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_client_secret"),
                        environment.getProperty("oidc_connect.client_secret"),
                        environment.getProperty("OIDC_CONNECT_CLIENT_SECRET")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_issuer_url"),
                        environment.getProperty("oidc_connect.issuer_url"),
                        environment.getProperty("OIDC_CONNECT_ISSUER_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_discovery_url"),
                        environment.getProperty("oidc_connect.discovery_url"),
                        environment.getProperty("OIDC_CONNECT_DISCOVERY_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_authorize_url"),
                        environment.getProperty("oidc_connect.authorize_url"),
                        environment.getProperty("OIDC_CONNECT_AUTHORIZE_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_token_url"),
                        environment.getProperty("oidc_connect.token_url"),
                        environment.getProperty("OIDC_CONNECT_TOKEN_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_userinfo_url"),
                        environment.getProperty("oidc_connect.userinfo_url"),
                        environment.getProperty("OIDC_CONNECT_USERINFO_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_jwks_url"),
                        environment.getProperty("oidc_connect.jwks_url"),
                        environment.getProperty("OIDC_CONNECT_JWKS_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_scopes"),
                        environment.getProperty("oidc_connect.scopes"),
                        environment.getProperty("OIDC_CONNECT_SCOPES"),
                        DEFAULT_SCOPES
                ),
                firstNonBlank(
                        settings.get("oidc_connect_redirect_url"),
                        environment.getProperty("oidc_connect.redirect_url"),
                        environment.getProperty("OIDC_CONNECT_REDIRECT_URL")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_frontend_redirect_url"),
                        environment.getProperty("oidc_connect.frontend_redirect_url"),
                        environment.getProperty("OIDC_CONNECT_FRONTEND_REDIRECT_URL"),
                        DEFAULT_FRONTEND_REDIRECT
                ),
                firstNonBlank(
                        settings.get("oidc_connect_token_auth_method"),
                        environment.getProperty("oidc_connect.token_auth_method"),
                        environment.getProperty("OIDC_CONNECT_TOKEN_AUTH_METHOD"),
                        DEFAULT_TOKEN_AUTH_METHOD
                ),
                resolveCompatibilityFlag(settings, "oidc_connect_use_pkce", "oidc_connect.use_pkce", true),
                resolveCompatibilityFlag(settings, "oidc_connect_validate_id_token", "oidc_connect.validate_id_token", true),
                firstNonBlank(
                        settings.get("oidc_connect_allowed_signing_algs"),
                        environment.getProperty("oidc_connect.allowed_signing_algs"),
                        environment.getProperty("OIDC_CONNECT_ALLOWED_SIGNING_ALGS"),
                        DEFAULT_ALLOWED_SIGNING_ALGS
                ),
                parseClockSkew(settings.get("oidc_connect_clock_skew_seconds"),
                        environment.getProperty("oidc_connect.clock_skew_seconds"),
                        environment.getProperty("OIDC_CONNECT_CLOCK_SKEW_SECONDS")),
                resolveCompatibilityFlag(settings, "oidc_connect_require_email_verified", "oidc_connect.require_email_verified", false),
                firstNonBlank(
                        settings.get("oidc_connect_userinfo_email_path"),
                        environment.getProperty("oidc_connect.userinfo_email_path"),
                        environment.getProperty("OIDC_CONNECT_USERINFO_EMAIL_PATH")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_userinfo_id_path"),
                        environment.getProperty("oidc_connect.userinfo_id_path"),
                        environment.getProperty("OIDC_CONNECT_USERINFO_ID_PATH")
                ),
                firstNonBlank(
                        settings.get("oidc_connect_userinfo_username_path"),
                        environment.getProperty("oidc_connect.userinfo_username_path"),
                        environment.getProperty("OIDC_CONNECT_USERINFO_USERNAME_PATH")
                )
        );
        return resolveEndpoints(raw);
    }

    private OidcOAuthConfig resolveEndpoints(OidcOAuthConfig raw) {
        String issuerUrl = trimToEmpty(raw.issuerUrl());
        String discoveryUrl = trimToEmpty(raw.discoveryUrl());
        String authorizeUrl = trimToEmpty(raw.authorizeUrl());
        String tokenUrl = trimToEmpty(raw.tokenUrl());
        String userInfoUrl = trimToEmpty(raw.userInfoUrl());
        String jwksUrl = trimToEmpty(raw.jwksUrl());

        boolean needsDiscovery = authorizeUrl.isBlank() || tokenUrl.isBlank()
                || (raw.validateIdToken() && (jwksUrl.isBlank() || issuerUrl.isBlank()));
        if (discoveryUrl.isBlank() && !issuerUrl.isBlank()) {
            discoveryUrl = buildDefaultDiscoveryUrl(issuerUrl);
        }
        if (needsDiscovery && !discoveryUrl.isBlank()) {
            Map<String, Object> discovery = fetchDiscovery(discoveryUrl);
            authorizeUrl = firstNonBlank(authorizeUrl, stringValue(discovery, "authorization_endpoint"));
            tokenUrl = firstNonBlank(tokenUrl, stringValue(discovery, "token_endpoint"));
            userInfoUrl = firstNonBlank(userInfoUrl, stringValue(discovery, "userinfo_endpoint"));
            jwksUrl = firstNonBlank(jwksUrl, stringValue(discovery, "jwks_uri"));
            issuerUrl = firstNonBlank(issuerUrl, stringValue(discovery, "issuer"));
        }

        return new OidcOAuthConfig(
                raw.enabled(),
                raw.providerName(),
                raw.clientId(),
                raw.clientSecret(),
                issuerUrl,
                discoveryUrl,
                authorizeUrl,
                tokenUrl,
                userInfoUrl,
                jwksUrl,
                raw.scopes(),
                raw.redirectUrl(),
                raw.frontendRedirectUrl(),
                raw.tokenAuthMethod(),
                raw.usePkce(),
                raw.validateIdToken(),
                raw.allowedSigningAlgs(),
                raw.clockSkewSeconds(),
                raw.requireEmailVerified(),
                raw.userInfoEmailPath(),
                raw.userInfoIdPath(),
                raw.userInfoUsernamePath()
        );
    }

    private Map<String, Object> fetchDiscovery(String discoveryUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(discoveryUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oidc discovery request failed");
            }
            Map<?, ?> raw = objectMapper.readValue(response.body(), Map.class);
            LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
            raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
            return mapped;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oidc discovery request failed");
        }
    }

    private String buildDefaultDiscoveryUrl(String issuerUrl) {
        String base = trimToEmpty(issuerUrl);
        if (base.endsWith("/")) {
            return base + ".well-known/openid-configuration";
        }
        return base + "/.well-known/openid-configuration";
    }

    private boolean resolveFlag(Map<String, String> settings, String dbKey, boolean defaultValue, String envKey) {
        if (settings.containsKey(dbKey)) {
            return isStrictTrue(settings.get(dbKey));
        }
        String envValue = firstNonBlank(
                environment.getProperty(envKey),
                environment.getProperty(envKey.toUpperCase(Locale.ROOT).replace('.', '_'))
        );
        if (!envValue.isBlank()) {
            return isStrictTrue(envValue);
        }
        return defaultValue;
    }

    private boolean resolveCompatibilityFlag(Map<String, String> settings, String dbKey, String envKey, boolean defaultValue) {
        if (settings.containsKey(dbKey) && !trimToEmpty(settings.get(dbKey)).isBlank()) {
            return isStrictTrue(settings.get(dbKey));
        }
        String envValue = firstNonBlank(
                environment.getProperty(envKey),
                environment.getProperty(envKey.toUpperCase(Locale.ROOT).replace('.', '_'))
        );
        if (!envValue.isBlank()) {
            return isStrictTrue(envValue);
        }
        return defaultValue;
    }

    private int parseClockSkew(String... values) {
        String raw = firstNonBlank(values);
        if (raw.isBlank()) {
            return DEFAULT_CLOCK_SKEW_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return Math.max(parsed, 0);
        } catch (NumberFormatException ex) {
            return DEFAULT_CLOCK_SKEW_SECONDS;
        }
    }

    private String normalizeTokenAuthMethod(String raw) {
        String normalized = firstNonBlank(raw, DEFAULT_TOKEN_AUTH_METHOD).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "client_secret_post", "client_secret_basic", "none" -> normalized;
            default -> throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth token_auth_method invalid");
        };
    }

    private void validateAbsoluteHttpUrl(String raw, String label) {
        String value = firstNonBlank(raw);
        if (value.isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth " + label + " url not configured");
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("missing host");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("invalid scheme");
            }
            if (uri.getFragment() != null && !uri.getFragment().isBlank()) {
                throw new IllegalArgumentException("fragment not allowed");
            }
        } catch (Exception ex) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth " + label + " url invalid");
        }
    }

    private void validateFrontendRedirectUrl(String raw) {
        String value = firstNonBlank(raw);
        if (value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth frontend redirect url invalid");
        }
        if (value.startsWith("//")) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth frontend redirect url invalid");
        }
        if (value.startsWith("/")) {
            return;
        }
        validateAbsoluteHttpUrl(value, "frontend redirect");
    }

    private boolean isStrictTrue(String raw) {
        return "true".equalsIgnoreCase(trimToEmpty(raw));
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null || key == null) {
            return "";
        }
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
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

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record OidcOAuthConfig(
            boolean enabled,
            String providerName,
            String clientId,
            String clientSecret,
            String issuerUrl,
            String discoveryUrl,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            String jwksUrl,
            String scopes,
            String redirectUrl,
            String frontendRedirectUrl,
            String tokenAuthMethod,
            boolean usePkce,
            boolean validateIdToken,
            String allowedSigningAlgs,
            int clockSkewSeconds,
            boolean requireEmailVerified,
            String userInfoEmailPath,
            String userInfoIdPath,
            String userInfoUsernamePath
    ) {
    }
}
