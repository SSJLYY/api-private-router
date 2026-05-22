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
public class EmailOAuthConfigService {

    private static final String DEFAULT_FRONTEND_REDIRECT = "/auth/oauth/callback";

    private final PublicSettingsRepository repository;
    private final Environment environment;

    public EmailOAuthConfigService(PublicSettingsRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    public EmailOAuthProviderConfig getRequiredConfig(String provider) {
        EmailOAuthProviderConfig config = getEffectiveConfig(provider);
        if (!config.enabled()) {
            throw new StructuredApiErrorException(404, "OAUTH_DISABLED", "oauth login is disabled");
        }
        if (config.clientId().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth client id not configured");
        }
        if (config.clientSecret().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth client secret not configured");
        }
        validateAbsoluteHttpUrl(config.authorizeUrl(), "authorize");
        validateAbsoluteHttpUrl(config.tokenUrl(), "token");
        validateAbsoluteHttpUrl(config.userInfoUrl(), "userinfo");
        validateAbsoluteHttpUrl(config.redirectUrl(), "redirect");
        if (!config.emailsUrl().isBlank()) {
            validateAbsoluteHttpUrl(config.emailsUrl(), "emails");
        }
        validateFrontendRedirectUrl(config.frontendRedirectUrl());
        return config;
    }

    public EmailOAuthProviderConfig getEffectiveConfig(String provider) {
        String normalizedProvider = normalizeProvider(provider);
        EmailOAuthProviderConfig defaults = switch (normalizedProvider) {
            case "github" -> new EmailOAuthProviderConfig(
                    true,
                    "",
                    "",
                    "https://github.com/login/oauth/authorize",
                    "https://github.com/login/oauth/access_token",
                    "https://api.github.com/user",
                    "https://api.github.com/user/emails",
                    "read:user user:email",
                    "",
                    DEFAULT_FRONTEND_REDIRECT,
                    ""
            );
            case "google" -> new EmailOAuthProviderConfig(
                    true,
                    "",
                    "",
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    "https://oauth2.googleapis.com/token",
                    "https://openidconnect.googleapis.com/v1/userinfo",
                    "",
                    "openid email profile",
                    "",
                    DEFAULT_FRONTEND_REDIRECT,
                    ""
            );
            default -> throw new StructuredApiErrorException(404, "OAUTH_PROVIDER_NOT_FOUND", "oauth provider not found");
        };

        Map<String, String> settings = repository.getValues(List.of(
                normalizedProvider + "_oauth_enabled",
                normalizedProvider + "_oauth_client_id",
                normalizedProvider + "_oauth_client_secret",
                normalizedProvider + "_oauth_redirect_url",
                normalizedProvider + "_oauth_frontend_redirect_url",
                "api_base_url"
        ));

        return new EmailOAuthProviderConfig(
                resolveFlag(settings, normalizedProvider + "_oauth_enabled", false, normalizedProvider + "_oauth.enabled"),
                firstNonBlank(
                        settings.get(normalizedProvider + "_oauth_client_id"),
                        environment.getProperty(normalizedProvider + "_oauth.client_id"),
                        environment.getProperty((normalizedProvider + "_oauth.client_id").toUpperCase(Locale.ROOT).replace('.', '_'))
                ),
                firstNonBlank(
                        settings.get(normalizedProvider + "_oauth_client_secret"),
                        environment.getProperty(normalizedProvider + "_oauth.client_secret"),
                        environment.getProperty((normalizedProvider + "_oauth.client_secret").toUpperCase(Locale.ROOT).replace('.', '_'))
                ),
                firstNonBlank(
                        environment.getProperty(normalizedProvider + "_oauth.authorize_url"),
                        environment.getProperty((normalizedProvider + "_oauth.authorize_url").toUpperCase(Locale.ROOT).replace('.', '_')),
                        defaults.authorizeUrl()
                ),
                firstNonBlank(
                        environment.getProperty(normalizedProvider + "_oauth.token_url"),
                        environment.getProperty((normalizedProvider + "_oauth.token_url").toUpperCase(Locale.ROOT).replace('.', '_')),
                        defaults.tokenUrl()
                ),
                firstNonBlank(
                        environment.getProperty(normalizedProvider + "_oauth.userinfo_url"),
                        environment.getProperty((normalizedProvider + "_oauth.userinfo_url").toUpperCase(Locale.ROOT).replace('.', '_')),
                        defaults.userInfoUrl()
                ),
                firstNonBlank(
                        environment.getProperty(normalizedProvider + "_oauth.emails_url"),
                        environment.getProperty((normalizedProvider + "_oauth.emails_url").toUpperCase(Locale.ROOT).replace('.', '_')),
                        defaults.emailsUrl()
                ),
                firstNonBlank(
                        environment.getProperty(normalizedProvider + "_oauth.scopes"),
                        environment.getProperty((normalizedProvider + "_oauth.scopes").toUpperCase(Locale.ROOT).replace('.', '_')),
                        defaults.scopes()
                ),
                firstNonBlank(
                        settings.get(normalizedProvider + "_oauth_redirect_url"),
                        environment.getProperty(normalizedProvider + "_oauth.redirect_url"),
                        environment.getProperty((normalizedProvider + "_oauth.redirect_url").toUpperCase(Locale.ROOT).replace('.', '_')),
                        resolveRedirectUrl(
                                normalizedProvider,
                                firstNonBlank(
                                        settings.get("api_base_url"),
                                        environment.getProperty("api_base_url"),
                                        environment.getProperty("API_BASE_URL")
                                )
                        )
                ),
                firstNonBlank(
                        settings.get(normalizedProvider + "_oauth_frontend_redirect_url"),
                        environment.getProperty(normalizedProvider + "_oauth.frontend_redirect_url"),
                        environment.getProperty((normalizedProvider + "_oauth.frontend_redirect_url").toUpperCase(Locale.ROOT).replace('.', '_')),
                        DEFAULT_FRONTEND_REDIRECT
                ),
                firstNonBlank(
                        settings.get("api_base_url"),
                        environment.getProperty("api_base_url"),
                        environment.getProperty("API_BASE_URL")
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

    private String resolveRedirectUrl(String provider, String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return "";
        }
        try {
            URI apiUri = URI.create(apiBaseUrl.trim());
            if (apiUri.getScheme() == null || apiUri.getHost() == null) {
                return "";
            }
            String path = apiUri.getPath() == null ? "" : apiUri.getPath();
            if (path.endsWith("/api/v1")) {
                return apiUri.getScheme() + "://" + apiUri.getAuthority() + path + "/auth/oauth/" + provider + "/callback";
            }
            return apiUri.getScheme() + "://" + apiUri.getAuthority() + path + "/api/v1/auth/oauth/" + provider + "/callback";
        } catch (IllegalArgumentException ex) {
            return "";
        }
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
        } catch (Exception ex) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "oauth frontend redirect url invalid");
        }
    }

    private boolean isStrictTrue(String raw) {
        return "true".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    private String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("github".equals(normalized) || "google".equals(normalized)) {
            return normalized;
        }
        return "";
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

    public record EmailOAuthProviderConfig(
            boolean enabled,
            String clientId,
            String clientSecret,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            String emailsUrl,
            String scopes,
            String redirectUrl,
            String frontendRedirectUrl,
            String apiBaseUrl
    ) {
    }
}
