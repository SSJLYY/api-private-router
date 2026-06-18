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
public class DingTalkOAuthConfigService {

    private static final String DEFAULT_AUTHORIZE_URL = "https://login.dingtalk.com/oauth2/auth";
    private static final String DEFAULT_TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";
    private static final String DEFAULT_USERINFO_URL = "https://api.dingtalk.com/v1.0/contact/users/me";
    private static final String DEFAULT_SCOPES = "openid";
    private static final String DEFAULT_FRONTEND_REDIRECT = "/auth/dingtalk/callback";

    private final PublicSettingsRepository repository;
    private final Environment environment;

    public DingTalkOAuthConfigService(PublicSettingsRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    public DingTalkOAuthConfig getRequiredConfig() {
        DingTalkOAuthConfig config = getEffectiveConfig();
        if (!config.enabled()) {
            throw new StructuredApiErrorException(404, "OAUTH_DISABLED", "dingtalk oauth login is disabled");
        }
        if (config.clientId().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth client id not configured");
        }
        if (config.clientSecret().isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth client secret not configured");
        }
        validateAbsoluteHttpUrl(config.authorizeUrl(), "authorize");
        validateAbsoluteHttpUrl(config.tokenUrl(), "token");
        validateAbsoluteHttpUrl(config.userInfoUrl(), "userinfo");
        validateAbsoluteHttpUrl(config.redirectUrl(), "redirect");
        validateFrontendRedirectUrl(config.frontendRedirectUrl());
        return config;
    }

    public DingTalkOAuthConfig getEffectiveConfig() {
        Map<String, String> settings = repository.getValues(List.of(
                "dingtalk_connect_enabled",
                "dingtalk_connect_client_id",
                "dingtalk_connect_client_secret",
                "dingtalk_connect_redirect_url"
        ));
        return new DingTalkOAuthConfig(
                resolveFlag(settings, "dingtalk_connect_enabled", false, "dingtalk_connect.enabled"),
                firstNonBlank(
                        settings.get("dingtalk_connect_client_id"),
                        environment.getProperty("dingtalk_connect.client_id"),
                        environment.getProperty("DINGTALK_CONNECT_CLIENT_ID")
                ),
                firstNonBlank(
                        settings.get("dingtalk_connect_client_secret"),
                        environment.getProperty("dingtalk_connect.client_secret"),
                        environment.getProperty("DINGTALK_CONNECT_CLIENT_SECRET")
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.authorize_url"),
                        environment.getProperty("DINGTALK_CONNECT_AUTHORIZE_URL"),
                        DEFAULT_AUTHORIZE_URL
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.token_url"),
                        environment.getProperty("DINGTALK_CONNECT_TOKEN_URL"),
                        DEFAULT_TOKEN_URL
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.userinfo_url"),
                        environment.getProperty("DINGTALK_CONNECT_USERINFO_URL"),
                        DEFAULT_USERINFO_URL
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.scopes"),
                        environment.getProperty("DINGTALK_CONNECT_SCOPES"),
                        DEFAULT_SCOPES
                ),
                firstNonBlank(
                        settings.get("dingtalk_connect_redirect_url"),
                        environment.getProperty("dingtalk_connect.redirect_url"),
                        environment.getProperty("DINGTALK_CONNECT_REDIRECT_URL")
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.frontend_redirect_url"),
                        environment.getProperty("DINGTALK_CONNECT_FRONTEND_REDIRECT_URL"),
                        DEFAULT_FRONTEND_REDIRECT
                ),
                isStrictTrue(firstNonBlank(
                        environment.getProperty("dingtalk_connect.require_email"),
                        environment.getProperty("DINGTALK_CONNECT_REQUIRE_EMAIL"),
                        "false"
                )),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.corp_restriction_policy"),
                        environment.getProperty("DINGTALK_CONNECT_CORP_RESTRICTION_POLICY")
                ),
                isStrictTrue(firstNonBlank(
                        environment.getProperty("dingtalk_connect.bypass_registration"),
                        environment.getProperty("DINGTALK_CONNECT_BYPASS_REGISTRATION"),
                        "false"
                )),
                isStrictTrue(firstNonBlank(
                        environment.getProperty("dingtalk_connect.sync_corp_email"),
                        environment.getProperty("DINGTALK_CONNECT_SYNC_CORP_EMAIL"),
                        "false"
                )),
                isStrictTrue(firstNonBlank(
                        environment.getProperty("dingtalk_connect.sync_display_name"),
                        environment.getProperty("DINGTALK_CONNECT_SYNC_DISPLAY_NAME"),
                        "false"
                )),
                isStrictTrue(firstNonBlank(
                        environment.getProperty("dingtalk_connect.sync_dept"),
                        environment.getProperty("DINGTALK_CONNECT_SYNC_DEPT"),
                        "false"
                )),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.sync_corp_email_attr_key"),
                        environment.getProperty("DINGTALK_CONNECT_SYNC_CORP_EMAIL_ATTR_KEY")
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.sync_display_name_attr_key"),
                        environment.getProperty("DINGTALK_CONNECT_SYNC_DISPLAY_NAME_ATTR_KEY")
                ),
                firstNonBlank(
                        environment.getProperty("dingtalk_connect.sync_dept_attr_key"),
                        environment.getProperty("DINGTALK_CONNECT_SYNC_DEPT_ATTR_KEY")
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

    private void validateAbsoluteHttpUrl(String raw, String label) {
        String value = firstNonBlank(raw);
        if (value.isBlank()) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth " + label + " url not configured");
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
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth " + label + " url invalid");
        }
    }

    private void validateFrontendRedirectUrl(String raw) {
        String value = firstNonBlank(raw);
        if (value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth frontend redirect url invalid");
        }
        if (value.startsWith("//")) {
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth frontend redirect url invalid");
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
            throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "dingtalk oauth frontend redirect url invalid");
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

    public record DingTalkOAuthConfig(
            boolean enabled,
            String clientId,
            String clientSecret,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            String scopes,
            String redirectUrl,
            String frontendRedirectUrl,
            boolean requireEmail,
            String corpRestrictionPolicy,
            boolean bypassRegistration,
            boolean syncCorpEmail,
            boolean syncDisplayName,
            boolean syncDept,
            String syncCorpEmailAttrKey,
            String syncDisplayNameAttrKey,
            String syncDeptAttrKey
    ) {
    }
}
