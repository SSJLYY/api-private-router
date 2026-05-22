package org.apiprivaterouter.javabackend.auth.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.publicsettings.repository.PublicSettingsRepository;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LinuxDoOAuthConfigService {

    private static final String DEFAULT_AUTHORIZE_URL = "https://connect.linux.do/oauth2/authorize";
    private static final String DEFAULT_TOKEN_URL = "https://connect.linux.do/oauth2/token";
    private static final String DEFAULT_USERINFO_URL = "https://connect.linux.do/api/user";
    private static final String DEFAULT_SCOPES = "user";
    private static final String DEFAULT_FRONTEND_REDIRECT = "/auth/community/callback";
    private static final String DEFAULT_TOKEN_AUTH_METHOD = "client_secret_post";

    private final PublicSettingsRepository repository;
    private final Environment environment;

    public LinuxDoOAuthConfigService(PublicSettingsRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    public LinuxDoOAuthConfig getRequiredConfig() {
        LinuxDoOAuthConfig config = getEffectiveConfig();
        if (!config.enabled()) {
            throw new StructuredApiErrorException(404, "OAUTH_DISABLED", "oauth login is disabled");
        }
        if (config.clientId().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth client id not configured");
        }
        validateAbsoluteHttpUrl(config.authorizeUrl(), "authorize");
        validateAbsoluteHttpUrl(config.tokenUrl(), "token");
        validateAbsoluteHttpUrl(config.userInfoUrl(), "userinfo");
        validateAbsoluteHttpUrl(config.redirectUrl(), "redirect");
        validateFrontendRedirectUrl(config.frontendRedirectUrl());
        String method = normalizeTokenAuthMethod(config.tokenAuthMethod());
        if (!"none".equals(method) && config.clientSecret().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth client secret not configured");
        }
        if (config.userInfoIdPath().isBlank() && config.userInfoUsernamePath().isBlank()) {
            // Allowed. The OAuth user-info mapper falls back to common field names.
        }
        return new LinuxDoOAuthConfig(
                config.enabled(),
                config.clientId(),
                config.clientSecret(),
                config.authorizeUrl(),
                config.tokenUrl(),
                config.userInfoUrl(),
                config.scopes(),
                config.redirectUrl(),
                config.frontendRedirectUrl(),
                method,
                config.usePkce(),
                config.userInfoEmailPath(),
                config.userInfoIdPath(),
                config.userInfoUsernamePath()
        );
    }

    public LinuxDoOAuthConfig getEffectiveConfig() {
        Map<String, String> settings = repository.getValues(List.of(
                "linuxdo_connect_enabled",
                "linuxdo_connect_client_id",
                "linuxdo_connect_client_secret",
                "linuxdo_connect_redirect_url"
        ));
        return new LinuxDoOAuthConfig(
                resolveFlag(settings, "linuxdo_connect_enabled", false, "linuxdo_connect.enabled"),
                firstNonBlank(
                        settings.get("linuxdo_connect_client_id"),
                        environment.getProperty("linuxdo_connect.client_id"),
                        environment.getProperty("LINUXDO_CONNECT_CLIENT_ID")
                ),
                firstNonBlank(
                        settings.get("linuxdo_connect_client_secret"),
                        environment.getProperty("linuxdo_connect.client_secret"),
                        environment.getProperty("LINUXDO_CONNECT_CLIENT_SECRET")
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.authorize_url"),
                        environment.getProperty("LINUXDO_CONNECT_AUTHORIZE_URL"),
                        DEFAULT_AUTHORIZE_URL
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.token_url"),
                        environment.getProperty("LINUXDO_CONNECT_TOKEN_URL"),
                        DEFAULT_TOKEN_URL
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.userinfo_url"),
                        environment.getProperty("LINUXDO_CONNECT_USERINFO_URL"),
                        DEFAULT_USERINFO_URL
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.scopes"),
                        environment.getProperty("LINUXDO_CONNECT_SCOPES"),
                        DEFAULT_SCOPES
                ),
                firstNonBlank(
                        settings.get("linuxdo_connect_redirect_url"),
                        environment.getProperty("linuxdo_connect.redirect_url"),
                        environment.getProperty("LINUXDO_CONNECT_REDIRECT_URL")
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.frontend_redirect_url"),
                        environment.getProperty("LINUXDO_CONNECT_FRONTEND_REDIRECT_URL"),
                        DEFAULT_FRONTEND_REDIRECT
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.token_auth_method"),
                        environment.getProperty("LINUXDO_CONNECT_TOKEN_AUTH_METHOD"),
                        DEFAULT_TOKEN_AUTH_METHOD
                ),
                isStrictTrue(firstNonBlank(
                        environment.getProperty("linuxdo_connect.use_pkce"),
                        environment.getProperty("LINUXDO_CONNECT_USE_PKCE"),
                        "false"
                )),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.userinfo_email_path"),
                        environment.getProperty("LINUXDO_CONNECT_USERINFO_EMAIL_PATH")
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.userinfo_id_path"),
                        environment.getProperty("LINUXDO_CONNECT_USERINFO_ID_PATH")
                ),
                firstNonBlank(
                        environment.getProperty("linuxdo_connect.userinfo_username_path"),
                        environment.getProperty("LINUXDO_CONNECT_USERINFO_USERNAME_PATH")
                )
        );
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
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth frontend redirect url invalid");
        }
    }

    private boolean isStrictTrue(String raw) {
        return "true".equalsIgnoreCase(raw == null ? "" : raw.trim());
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

    public record LinuxDoOAuthConfig(
            boolean enabled,
            String clientId,
            String clientSecret,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            String scopes,
            String redirectUrl,
            String frontendRedirectUrl,
            String tokenAuthMethod,
            boolean usePkce,
            String userInfoEmailPath,
            String userInfoIdPath,
            String userInfoUsernamePath
    ) {
    }
}
