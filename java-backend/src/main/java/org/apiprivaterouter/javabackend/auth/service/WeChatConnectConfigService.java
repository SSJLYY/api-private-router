package org.apiprivaterouter.javabackend.auth.service;

import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.publicsettings.repository.PublicSettingsRepository;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WeChatConnectConfigService {

    private static final String DEFAULT_FRONTEND_REDIRECT = "/auth/wechat/callback";
    private static final String DEFAULT_OPEN_SCOPE = "snsapi_login";

    private final PublicSettingsRepository repository;
    private final Environment environment;

    public WeChatConnectConfigService(PublicSettingsRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    public WeChatConnectConfig getRequiredConfig() {
        WeChatConnectConfig config = getEffectiveConfig();
        if (!config.enabled() || (!config.openEnabled() && !config.mpEnabled())) {
            throw new StructuredApiErrorException(404, "OAUTH_DISABLED", "wechat oauth is disabled");
        }
        if (config.openEnabled()) {
            if (config.appIdForMode("open").isBlank()) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "wechat oauth pc app id not configured");
            }
            if (config.appSecretForMode("open").isBlank()) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "wechat oauth pc app secret not configured");
            }
        }
        if (config.mpEnabled()) {
            if (config.appIdForMode("mp").isBlank()) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "wechat oauth official account app id not configured");
            }
            if (config.appSecretForMode("mp").isBlank()) {
                throw new StructuredApiErrorException(500, "OAUTH_CONFIG_INVALID", "wechat oauth official account app secret not configured");
            }
        }
        return config;
    }

    public WeChatConnectConfig getEffectiveConfig() {
        Map<String, String> settings = repository.getValues(List.of(
                "wechat_connect_enabled",
                "wechat_connect_app_id",
                "wechat_connect_app_secret",
                "wechat_connect_open_app_id",
                "wechat_connect_open_app_secret",
                "wechat_connect_mp_app_id",
                "wechat_connect_mp_app_secret",
                "wechat_connect_mobile_app_id",
                "wechat_connect_mobile_app_secret",
                "wechat_connect_open_enabled",
                "wechat_connect_mp_enabled",
                "wechat_connect_mobile_enabled",
                "wechat_connect_mode",
                "wechat_connect_scopes",
                "wechat_connect_redirect_url",
                "wechat_connect_frontend_redirect_url",
                "api_base_url"
        ));

        boolean enabled = resolveFlag(settings, "wechat_connect_enabled", false, "wechat_connect.enabled");
        String mode = normalizeMode(firstNonBlank(
                settings.get("wechat_connect_mode"),
                environment.getProperty("wechat_connect.mode"),
                environment.getProperty("WECHAT_CONNECT_MODE"),
                "open"
        ));

        String legacyAppId = firstNonBlank(
                settings.get("wechat_connect_app_id"),
                environment.getProperty("wechat_connect.app_id"),
                environment.getProperty("WECHAT_CONNECT_APP_ID"),
                environment.getProperty("wechat_connect.open_app_id"),
                environment.getProperty("WECHAT_CONNECT_OPEN_APP_ID"),
                environment.getProperty("wechat_connect.mp_app_id"),
                environment.getProperty("WECHAT_CONNECT_MP_APP_ID"),
                environment.getProperty("wechat_connect.mobile_app_id"),
                environment.getProperty("WECHAT_CONNECT_MOBILE_APP_ID")
        );
        String legacyAppSecret = firstNonBlank(
                settings.get("wechat_connect_app_secret"),
                environment.getProperty("wechat_connect.app_secret"),
                environment.getProperty("WECHAT_CONNECT_APP_SECRET"),
                environment.getProperty("wechat_connect.open_app_secret"),
                environment.getProperty("WECHAT_CONNECT_OPEN_APP_SECRET"),
                environment.getProperty("wechat_connect.mp_app_secret"),
                environment.getProperty("WECHAT_CONNECT_MP_APP_SECRET"),
                environment.getProperty("wechat_connect.mobile_app_secret"),
                environment.getProperty("WECHAT_CONNECT_MOBILE_APP_SECRET")
        );

        String openAppId = firstNonBlank(
                settings.get("wechat_connect_open_app_id"),
                environment.getProperty("wechat_connect.open_app_id"),
                environment.getProperty("WECHAT_CONNECT_OPEN_APP_ID"),
                legacyAppId
        );
        String openAppSecret = firstNonBlank(
                settings.get("wechat_connect_open_app_secret"),
                environment.getProperty("wechat_connect.open_app_secret"),
                environment.getProperty("WECHAT_CONNECT_OPEN_APP_SECRET"),
                legacyAppSecret
        );
        String mpAppId = firstNonBlank(
                settings.get("wechat_connect_mp_app_id"),
                environment.getProperty("wechat_connect.mp_app_id"),
                environment.getProperty("WECHAT_CONNECT_MP_APP_ID"),
                legacyAppId
        );
        String mpAppSecret = firstNonBlank(
                settings.get("wechat_connect_mp_app_secret"),
                environment.getProperty("wechat_connect.mp_app_secret"),
                environment.getProperty("WECHAT_CONNECT_MP_APP_SECRET"),
                legacyAppSecret
        );
        String mobileAppId = firstNonBlank(
                settings.get("wechat_connect_mobile_app_id"),
                environment.getProperty("wechat_connect.mobile_app_id"),
                environment.getProperty("WECHAT_CONNECT_MOBILE_APP_ID"),
                legacyAppId
        );
        String mobileAppSecret = firstNonBlank(
                settings.get("wechat_connect_mobile_app_secret"),
                environment.getProperty("wechat_connect.mobile_app_secret"),
                environment.getProperty("WECHAT_CONNECT_MOBILE_APP_SECRET"),
                legacyAppSecret
        );

        boolean envOpenEnabled = resolveEnvironmentFlag(false, "wechat_connect.open_enabled");
        boolean envMpEnabled = resolveEnvironmentFlag(false, "wechat_connect.mp_enabled");
        boolean envMobileEnabled = resolveEnvironmentFlag(false, "wechat_connect.mobile_enabled");
        WeChatModeFlags flags = mergeModeFlags(settings, enabled, envOpenEnabled, envMpEnabled, envMobileEnabled, mode);

        return new WeChatConnectConfig(
                enabled,
                legacyAppId,
                legacyAppSecret,
                openAppId,
                openAppSecret,
                mpAppId,
                mpAppSecret,
                mobileAppId,
                mobileAppSecret,
                flags.openEnabled(),
                flags.mpEnabled(),
                flags.mobileEnabled(),
                mode,
                normalizeScope(firstNonBlank(
                        settings.get("wechat_connect_scopes"),
                        environment.getProperty("wechat_connect.scopes"),
                        environment.getProperty("WECHAT_CONNECT_SCOPES")
                ), mode),
                firstNonBlank(
                        settings.get("wechat_connect_redirect_url"),
                        environment.getProperty("wechat_connect.redirect_url"),
                        environment.getProperty("WECHAT_CONNECT_REDIRECT_URL")
                ),
                firstNonBlank(
                        settings.get("wechat_connect_frontend_redirect_url"),
                        environment.getProperty("wechat_connect.frontend_redirect_url"),
                        environment.getProperty("WECHAT_CONNECT_FRONTEND_REDIRECT_URL"),
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

    private boolean resolveEnvironmentFlag(boolean defaultValue, String envKey) {
        String envValue = firstNonBlank(
                environment.getProperty(envKey),
                environment.getProperty(envKey.toUpperCase(Locale.ROOT).replace('.', '_'))
        );
        if (envValue.isBlank()) {
            return defaultValue;
        }
        return isStrictTrue(envValue);
    }

    private WeChatModeFlags mergeModeFlags(
            Map<String, String> settings,
            boolean enabled,
            boolean baseOpenEnabled,
            boolean baseMpEnabled,
            boolean baseMobileEnabled,
            String mode
    ) {
        String rawOpen = settings.get("wechat_connect_open_enabled");
        String rawMp = settings.get("wechat_connect_mp_enabled");
        String rawMobile = settings.get("wechat_connect_mobile_enabled");
        boolean openConfigured = rawOpen != null && !rawOpen.trim().isEmpty();
        boolean mpConfigured = rawMp != null && !rawMp.trim().isEmpty();
        boolean mobileConfigured = rawMobile != null && !rawMobile.trim().isEmpty();

        if (openConfigured || mpConfigured || mobileConfigured) {
            boolean openEnabled = isStrictTrue(rawOpen);
            boolean mpEnabled = isStrictTrue(rawMp);
            boolean mobileEnabled = isStrictTrue(rawMobile);
            if (!settings.containsKey("wechat_connect_enabled")
                    && enabled
                    && !openEnabled
                    && !mpEnabled
                    && !mobileEnabled
                    && (baseOpenEnabled || baseMpEnabled || baseMobileEnabled)) {
                return new WeChatModeFlags(baseOpenEnabled, baseMpEnabled, baseMobileEnabled);
            }
            return new WeChatModeFlags(openEnabled, mpEnabled, mobileEnabled);
        }

        if (!enabled) {
            return new WeChatModeFlags(false, false, false);
        }
        if (baseOpenEnabled || baseMpEnabled || baseMobileEnabled) {
            return new WeChatModeFlags(baseOpenEnabled, baseMpEnabled, baseMobileEnabled);
        }
        return switch (mode) {
            case "mp" -> new WeChatModeFlags(false, true, false);
            case "mobile" -> new WeChatModeFlags(false, false, true);
            default -> new WeChatModeFlags(true, false, false);
        };
    }

    private String normalizeMode(String rawMode) {
        String normalized = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mp" -> "mp";
            case "mobile" -> "mobile";
            default -> "open";
        };
    }

    private String normalizeScope(String raw, String mode) {
        String normalizedMode = normalizeMode(mode);
        if ("mp".equals(normalizedMode)) {
            String scope = raw == null ? "" : raw.trim();
            return switch (scope) {
                case "snsapi_base" -> "snsapi_base";
                case "snsapi_userinfo" -> "snsapi_userinfo";
                default -> "snsapi_userinfo";
            };
        }
        if ("mobile".equals(normalizedMode)) {
            return "";
        }
        return DEFAULT_OPEN_SCOPE;
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

    private record WeChatModeFlags(boolean openEnabled, boolean mpEnabled, boolean mobileEnabled) {
    }

    public record WeChatConnectConfig(
            boolean enabled,
            String legacyAppId,
            String legacyAppSecret,
            String openAppId,
            String openAppSecret,
            String mpAppId,
            String mpAppSecret,
            String mobileAppId,
            String mobileAppSecret,
            boolean openEnabled,
            boolean mpEnabled,
            boolean mobileEnabled,
            String mode,
            String scopes,
            String redirectUrl,
            String frontendRedirectUrl,
            String apiBaseUrl
    ) {
        public boolean supportsMode(String requestedMode) {
            return switch (requestedMode == null ? "" : requestedMode.trim()) {
                case "mp" -> mpEnabled;
                case "mobile" -> mobileEnabled;
                default -> openEnabled;
            };
        }

        public String appIdForMode(String requestedMode) {
            return switch (requestedMode == null ? "" : requestedMode.trim()) {
                case "mp" -> firstNonBlankStatic(mpAppId, legacyAppId);
                case "mobile" -> firstNonBlankStatic(mobileAppId, legacyAppId);
                default -> firstNonBlankStatic(openAppId, legacyAppId);
            };
        }

        public String appSecretForMode(String requestedMode) {
            return switch (requestedMode == null ? "" : requestedMode.trim()) {
                case "mp" -> firstNonBlankStatic(mpAppSecret, legacyAppSecret);
                case "mobile" -> firstNonBlankStatic(mobileAppSecret, legacyAppSecret);
                default -> firstNonBlankStatic(openAppSecret, legacyAppSecret);
            };
        }

        public String scopeForMode(String requestedMode) {
            if ("mp".equals(requestedMode)) {
                return scopes == null || scopes.isBlank() ? "snsapi_userinfo" : scopes;
            }
            if ("mobile".equals(requestedMode)) {
                return "";
            }
            return DEFAULT_OPEN_SCOPE;
        }

        private static String firstNonBlankStatic(String... values) {
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
    }
}
