package org.apiprivaterouter.javabackend.publicsettings.service;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomEndpoint;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomMenuItem;
import org.apiprivaterouter.javabackend.publicsettings.model.PublicSettingsResponse;
import org.apiprivaterouter.javabackend.publicsettings.repository.PublicSettingsRepository;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
// TODO: Add caching (e.g. @Cacheable) to avoid hitting the database on every request — settings rarely change
public class PublicSettingsService {

    private static final String KEY_REGISTRATION_ENABLED = "registration_enabled";
    private static final String KEY_EMAIL_VERIFY_ENABLED = "email_verify_enabled";
    private static final String KEY_FORCE_EMAIL_ON_THIRD_PARTY_SIGNUP = "force_email_on_third_party_signup";
    private static final String KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST = "registration_email_suffix_whitelist";
    private static final String KEY_PROMO_CODE_ENABLED = "promo_code_enabled";
    private static final String KEY_PASSWORD_RESET_ENABLED = "password_reset_enabled";
    private static final String KEY_INVITATION_CODE_ENABLED = "invitation_code_enabled";
    private static final String KEY_TOTP_ENABLED = "totp_enabled";
    private static final String KEY_TURNSTILE_ENABLED = "turnstile_enabled";
    private static final String KEY_TURNSTILE_SITE_KEY = "turnstile_site_key";
    private static final String KEY_SITE_NAME = "site_name";
    private static final String KEY_SITE_LOGO = "site_logo";
    private static final String KEY_SITE_SUBTITLE = "site_subtitle";
    private static final String KEY_API_BASE_URL = "api_base_url";
    private static final String KEY_CONTACT_INFO = "contact_info";
    private static final String KEY_DOC_URL = "doc_url";
    private static final String KEY_HOME_CONTENT = "home_content";
    private static final String KEY_HIDE_CCS_IMPORT_BUTTON = "hide_ccs_import_button";
    private static final String KEY_PURCHASE_SUBSCRIPTION_ENABLED = "purchase_subscription_enabled";
    private static final String KEY_PURCHASE_SUBSCRIPTION_URL = "purchase_subscription_url";
    private static final String KEY_TABLE_DEFAULT_PAGE_SIZE = "table_default_page_size";
    private static final String KEY_TABLE_PAGE_SIZE_OPTIONS = "table_page_size_options";
    private static final String KEY_CUSTOM_MENU_ITEMS = "custom_menu_items";
    private static final String KEY_CUSTOM_ENDPOINTS = "custom_endpoints";
    private static final String KEY_LINUXDO_CONNECT_ENABLED = "linuxdo_connect_enabled";
    private static final String KEY_WECHAT_CONNECT_ENABLED = "wechat_connect_enabled";
    private static final String KEY_WECHAT_CONNECT_APP_ID = "wechat_connect_app_id";
    private static final String KEY_WECHAT_CONNECT_APP_SECRET = "wechat_connect_app_secret";
    private static final String KEY_WECHAT_CONNECT_OPEN_APP_ID = "wechat_connect_open_app_id";
    private static final String KEY_WECHAT_CONNECT_OPEN_APP_SECRET = "wechat_connect_open_app_secret";
    private static final String KEY_WECHAT_CONNECT_MP_APP_ID = "wechat_connect_mp_app_id";
    private static final String KEY_WECHAT_CONNECT_MP_APP_SECRET = "wechat_connect_mp_app_secret";
    private static final String KEY_WECHAT_CONNECT_MOBILE_APP_ID = "wechat_connect_mobile_app_id";
    private static final String KEY_WECHAT_CONNECT_MOBILE_APP_SECRET = "wechat_connect_mobile_app_secret";
    private static final String KEY_WECHAT_CONNECT_OPEN_ENABLED = "wechat_connect_open_enabled";
    private static final String KEY_WECHAT_CONNECT_MP_ENABLED = "wechat_connect_mp_enabled";
    private static final String KEY_WECHAT_CONNECT_MOBILE_ENABLED = "wechat_connect_mobile_enabled";
    private static final String KEY_WECHAT_CONNECT_MODE = "wechat_connect_mode";
    private static final String KEY_OIDC_CONNECT_ENABLED = "oidc_connect_enabled";
    private static final String KEY_OIDC_CONNECT_PROVIDER_NAME = "oidc_connect_provider_name";
    private static final String KEY_GITHUB_OAUTH_ENABLED = "github_oauth_enabled";
    private static final String KEY_GITHUB_OAUTH_CLIENT_ID = "github_oauth_client_id";
    private static final String KEY_GITHUB_OAUTH_CLIENT_SECRET = "github_oauth_client_secret";
    private static final String KEY_GOOGLE_OAUTH_ENABLED = "google_oauth_enabled";
    private static final String KEY_GOOGLE_OAUTH_CLIENT_ID = "google_oauth_client_id";
    private static final String KEY_GOOGLE_OAUTH_CLIENT_SECRET = "google_oauth_client_secret";
    private static final String KEY_BACKEND_MODE_ENABLED = "backend_mode_enabled";
    private static final String KEY_PAYMENT_ENABLED = "payment_enabled";
    private static final String KEY_BALANCE_LOW_NOTIFY_ENABLED = "balance_low_notify_enabled";
    private static final String KEY_ACCOUNT_QUOTA_NOTIFY_ENABLED = "account_quota_notify_enabled";
    private static final String KEY_BALANCE_LOW_NOTIFY_THRESHOLD = "balance_low_notify_threshold";
    private static final String KEY_BALANCE_LOW_NOTIFY_RECHARGE_URL = "balance_low_notify_recharge_url";
    private static final String KEY_CHANNEL_MONITOR_ENABLED = "channel_monitor_enabled";
    private static final String KEY_CHANNEL_MONITOR_DEFAULT_INTERVAL_SECONDS = "channel_monitor_default_interval_seconds";
    private static final String KEY_AVAILABLE_CHANNELS_ENABLED = "available_channels_enabled";
    private static final String KEY_AFFILIATE_ENABLED = "affiliate_enabled";
    private static final String KEY_RISK_CONTROL_ENABLED = "risk_control_enabled";
    private static final String KEY_REDPACKET_ENABLED = "redpacket_enabled";
    private static final String KEY_GAME_HALL_ENABLED = "game_hall_enabled";
    private static final String KEY_TRANSFER_ENABLED = "transfer_enabled";
    private static final String KEY_FUND_CENTER_ENABLED = "fund_center_enabled";

    private static final String DEFAULT_VERSION = "";
    private static final String DEFAULT_SITE_NAME = "api-private-router";
    private static final String DEFAULT_SITE_SUBTITLE = "Unified AI access for your organization";
    private static final String DEFAULT_OIDC_PROVIDER_NAME = "OIDC";
    private static final String DEFAULT_WECHAT_MODE = "open";
    private static final int DEFAULT_TABLE_PAGE_SIZE = 20;
    private static final List<Integer> DEFAULT_TABLE_PAGE_OPTIONS = List.of(10, 20, 50);
    private static final int CHANNEL_MONITOR_INTERVAL_MIN = 15;
    private static final int CHANNEL_MONITOR_INTERVAL_MAX = 3600;
    private static final int CHANNEL_MONITOR_INTERVAL_FALLBACK = 60;
    private static final Pattern EMAIL_DOMAIN_PATTERN = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"
    );

    private final PublicSettingsRepository repository;
    private final JsonHelper jsonHelper;
    private final Environment environment;

    public PublicSettingsService(
            PublicSettingsRepository repository,
            JsonHelper jsonHelper,
            Environment environment
    ) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.environment = environment;
    }

    public PublicSettingsResponse getPublicSettings() {
        Map<String, String> settings = repository.getValues(List.of(
                KEY_REGISTRATION_ENABLED,
                KEY_EMAIL_VERIFY_ENABLED,
                KEY_FORCE_EMAIL_ON_THIRD_PARTY_SIGNUP,
                KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST,
                KEY_PROMO_CODE_ENABLED,
                KEY_PASSWORD_RESET_ENABLED,
                KEY_INVITATION_CODE_ENABLED,
                KEY_TOTP_ENABLED,
                KEY_TURNSTILE_ENABLED,
                KEY_TURNSTILE_SITE_KEY,
                KEY_SITE_NAME,
                KEY_SITE_LOGO,
                KEY_SITE_SUBTITLE,
                KEY_API_BASE_URL,
                KEY_CONTACT_INFO,
                KEY_DOC_URL,
                KEY_HOME_CONTENT,
                KEY_HIDE_CCS_IMPORT_BUTTON,
                KEY_PURCHASE_SUBSCRIPTION_ENABLED,
                KEY_PURCHASE_SUBSCRIPTION_URL,
                KEY_TABLE_DEFAULT_PAGE_SIZE,
                KEY_TABLE_PAGE_SIZE_OPTIONS,
                KEY_CUSTOM_MENU_ITEMS,
                KEY_CUSTOM_ENDPOINTS,
                KEY_LINUXDO_CONNECT_ENABLED,
                KEY_WECHAT_CONNECT_ENABLED,
                KEY_WECHAT_CONNECT_APP_ID,
                KEY_WECHAT_CONNECT_APP_SECRET,
                KEY_WECHAT_CONNECT_OPEN_APP_ID,
                KEY_WECHAT_CONNECT_OPEN_APP_SECRET,
                KEY_WECHAT_CONNECT_MP_APP_ID,
                KEY_WECHAT_CONNECT_MP_APP_SECRET,
                KEY_WECHAT_CONNECT_MOBILE_APP_ID,
                KEY_WECHAT_CONNECT_MOBILE_APP_SECRET,
                KEY_WECHAT_CONNECT_OPEN_ENABLED,
                KEY_WECHAT_CONNECT_MP_ENABLED,
                KEY_WECHAT_CONNECT_MOBILE_ENABLED,
                KEY_WECHAT_CONNECT_MODE,
                KEY_OIDC_CONNECT_ENABLED,
                KEY_OIDC_CONNECT_PROVIDER_NAME,
                KEY_GITHUB_OAUTH_ENABLED,
                KEY_GITHUB_OAUTH_CLIENT_ID,
                KEY_GITHUB_OAUTH_CLIENT_SECRET,
                KEY_GOOGLE_OAUTH_ENABLED,
                KEY_GOOGLE_OAUTH_CLIENT_ID,
                KEY_GOOGLE_OAUTH_CLIENT_SECRET,
                KEY_BACKEND_MODE_ENABLED,
                KEY_PAYMENT_ENABLED,
                KEY_BALANCE_LOW_NOTIFY_ENABLED,
                KEY_ACCOUNT_QUOTA_NOTIFY_ENABLED,
                KEY_BALANCE_LOW_NOTIFY_THRESHOLD,
                KEY_BALANCE_LOW_NOTIFY_RECHARGE_URL,
                KEY_CHANNEL_MONITOR_ENABLED,
                KEY_CHANNEL_MONITOR_DEFAULT_INTERVAL_SECONDS,
                KEY_AVAILABLE_CHANNELS_ENABLED,
                KEY_AFFILIATE_ENABLED,
                KEY_RISK_CONTROL_ENABLED,
                KEY_REDPACKET_ENABLED,
                KEY_GAME_HALL_ENABLED,
                KEY_TRANSFER_ENABLED,
                KEY_FUND_CENTER_ENABLED
        ));

        boolean emailVerifyEnabled = isStrictTrue(settings.get(KEY_EMAIL_VERIFY_ENABLED));
        boolean passwordResetEnabled = emailVerifyEnabled && isStrictTrue(settings.get(KEY_PASSWORD_RESET_ENABLED));
        int tableDefaultPageSize = parseTableDefaultPageSize(settings.get(KEY_TABLE_DEFAULT_PAGE_SIZE));
        List<Integer> tablePageSizeOptions = parseTablePageSizeOptions(settings.get(KEY_TABLE_PAGE_SIZE_OPTIONS));
        double balanceLowNotifyThreshold = parseNonNegativeDouble(settings.get(KEY_BALANCE_LOW_NOTIFY_THRESHOLD));

        boolean linuxDoEnabled = resolveFlag(settings, KEY_LINUXDO_CONNECT_ENABLED, false, "linuxdo_connect.enabled");
        boolean oidcEnabled = resolveFlag(settings, KEY_OIDC_CONNECT_ENABLED, false, "oidc_connect.enabled");
        String oidcProviderName = firstNonBlank(
                settings.get(KEY_OIDC_CONNECT_PROVIDER_NAME),
                environment.getProperty("oidc_connect.provider_name"),
                environment.getProperty("OIDC_CONNECT_PROVIDER_NAME"),
                DEFAULT_OIDC_PROVIDER_NAME
        );

        boolean githubEnabled = emailOAuthPublicEnabled(
                settings,
                KEY_GITHUB_OAUTH_ENABLED,
                KEY_GITHUB_OAUTH_CLIENT_ID,
                KEY_GITHUB_OAUTH_CLIENT_SECRET,
                "github_oauth.enabled",
                "github_oauth.client_id",
                "github_oauth.client_secret"
        );
        boolean googleEnabled = emailOAuthPublicEnabled(
                settings,
                KEY_GOOGLE_OAUTH_ENABLED,
                KEY_GOOGLE_OAUTH_CLIENT_ID,
                KEY_GOOGLE_OAUTH_CLIENT_SECRET,
                "google_oauth.enabled",
                "google_oauth.client_id",
                "google_oauth.client_secret"
        );

        WeChatCapabilities weChat = resolveWeChatCapabilities(settings);

        return new PublicSettingsResponse(
                isStrictTrue(settings.get(KEY_REGISTRATION_ENABLED)),
                emailVerifyEnabled,
                isStrictTrue(settings.get(KEY_FORCE_EMAIL_ON_THIRD_PARTY_SIGNUP)),
                parseRegistrationEmailSuffixWhitelist(settings.get(KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
                !"false".equals(trimmedLower(settings.get(KEY_PROMO_CODE_ENABLED))),
                passwordResetEnabled,
                isStrictTrue(settings.get(KEY_INVITATION_CODE_ENABLED)),
                isStrictTrue(settings.get(KEY_TOTP_ENABLED)),
                isStrictTrue(settings.get(KEY_TURNSTILE_ENABLED)),
                defaultString(settings.get(KEY_TURNSTILE_SITE_KEY)),
                firstNonBlank(settings.get(KEY_SITE_NAME), DEFAULT_SITE_NAME),
                defaultString(settings.get(KEY_SITE_LOGO)),
                firstNonBlank(settings.get(KEY_SITE_SUBTITLE), DEFAULT_SITE_SUBTITLE),
                defaultString(settings.get(KEY_API_BASE_URL)),
                defaultString(settings.get(KEY_CONTACT_INFO)),
                defaultString(settings.get(KEY_DOC_URL)),
                defaultString(settings.get(KEY_HOME_CONTENT)),
                isStrictTrue(settings.get(KEY_HIDE_CCS_IMPORT_BUTTON)),
                isStrictTrue(settings.get(KEY_PURCHASE_SUBSCRIPTION_ENABLED)),
                defaultString(settings.get(KEY_PURCHASE_SUBSCRIPTION_URL)).trim(),
                tableDefaultPageSize,
                tablePageSizeOptions,
                parseUserVisibleMenuItems(settings.get(KEY_CUSTOM_MENU_ITEMS)),
                parseCustomEndpoints(settings.get(KEY_CUSTOM_ENDPOINTS)),
                linuxDoEnabled,
                weChat.webEnabled(),
                weChat.openEnabled(),
                weChat.mpEnabled(),
                weChat.mobileEnabled(),
                oidcEnabled,
                oidcProviderName,
                githubEnabled,
                googleEnabled,
                false,
                isStrictTrue(settings.get(KEY_BACKEND_MODE_ENABLED)),
                isStrictTrue(settings.get(KEY_PAYMENT_ENABLED)),
                resolveVersion(),
                isStrictTrue(settings.get(KEY_BALANCE_LOW_NOTIFY_ENABLED)),
                isStrictTrue(settings.get(KEY_ACCOUNT_QUOTA_NOTIFY_ENABLED)),
                balanceLowNotifyThreshold,
                defaultString(settings.get(KEY_BALANCE_LOW_NOTIFY_RECHARGE_URL)),
                !isFalseSettingValue(settings.get(KEY_CHANNEL_MONITOR_ENABLED)),
                parseChannelMonitorInterval(settings.get(KEY_CHANNEL_MONITOR_DEFAULT_INTERVAL_SECONDS)),
                isStrictTrue(settings.get(KEY_AVAILABLE_CHANNELS_ENABLED)),
                isStrictTrue(settings.get(KEY_AFFILIATE_ENABLED)),
                isStrictTrue(settings.get(KEY_RISK_CONTROL_ENABLED)),
                isStrictTrue(settings.get(KEY_REDPACKET_ENABLED)),
                isStrictTrue(settings.get(KEY_GAME_HALL_ENABLED)),
                isStrictTrue(settings.get(KEY_TRANSFER_ENABLED)),
                isStrictTrue(settings.get(KEY_FUND_CENTER_ENABLED))
        );
    }

    private boolean emailOAuthPublicEnabled(
            Map<String, String> settings,
            String enabledKey,
            String clientIdKey,
            String clientSecretKey,
            String enabledPropertyKey,
            String clientIdPropertyKey,
            String clientSecretPropertyKey
    ) {
        boolean enabled = resolveFlag(settings, enabledKey, false, enabledPropertyKey);
        String clientId = firstNonBlank(
                settings.get(clientIdKey),
                environment.getProperty(clientIdPropertyKey),
                environment.getProperty(clientIdPropertyKey.toUpperCase().replace('.', '_'))
        );
        String clientSecret = firstNonBlank(
                settings.get(clientSecretKey),
                environment.getProperty(clientSecretPropertyKey),
                environment.getProperty(clientSecretPropertyKey.toUpperCase().replace('.', '_'))
        );
        return enabled && !clientId.isBlank() && !clientSecret.isBlank();
    }

    private boolean resolveFlag(
            Map<String, String> settings,
            String dbKey,
            boolean defaultValue,
            String propertyKey
    ) {
        if (settings.containsKey(dbKey)) {
            return isStrictTrue(settings.get(dbKey));
        }
        String envValue = firstNonBlank(
                environment.getProperty(propertyKey),
                environment.getProperty(propertyKey.toUpperCase().replace('.', '_'))
        );
        if (!envValue.isBlank()) {
            return isStrictTrue(envValue);
        }
        return defaultValue;
    }

    private WeChatCapabilities resolveWeChatCapabilities(Map<String, String> settings) {
        boolean baseEnabled = resolveFlag(settings, KEY_WECHAT_CONNECT_ENABLED, false, "wechat_connect.enabled");
        String baseAppId = firstNonBlank(
                environment.getProperty("wechat_connect.app_id"),
                environment.getProperty("WECHAT_CONNECT_APP_ID")
        );
        String baseAppSecret = firstNonBlank(
                environment.getProperty("wechat_connect.app_secret"),
                environment.getProperty("WECHAT_CONNECT_APP_SECRET")
        );
        String baseOpenAppId = firstNonBlank(
                environment.getProperty("wechat_connect.open_app_id"),
                environment.getProperty("WECHAT_CONNECT_OPEN_APP_ID")
        );
        String baseOpenAppSecret = firstNonBlank(
                environment.getProperty("wechat_connect.open_app_secret"),
                environment.getProperty("WECHAT_CONNECT_OPEN_APP_SECRET")
        );
        String baseMpAppId = firstNonBlank(
                environment.getProperty("wechat_connect.mp_app_id"),
                environment.getProperty("WECHAT_CONNECT_MP_APP_ID")
        );
        String baseMpAppSecret = firstNonBlank(
                environment.getProperty("wechat_connect.mp_app_secret"),
                environment.getProperty("WECHAT_CONNECT_MP_APP_SECRET")
        );
        String baseMobileAppId = firstNonBlank(
                environment.getProperty("wechat_connect.mobile_app_id"),
                environment.getProperty("WECHAT_CONNECT_MOBILE_APP_ID")
        );
        String baseMobileAppSecret = firstNonBlank(
                environment.getProperty("wechat_connect.mobile_app_secret"),
                environment.getProperty("WECHAT_CONNECT_MOBILE_APP_SECRET")
        );
        boolean baseOpenEnabled = resolveEnvironmentFlag(false, "wechat_connect.open_enabled");
        boolean baseMpEnabled = resolveEnvironmentFlag(false, "wechat_connect.mp_enabled");
        boolean baseMobileEnabled = resolveEnvironmentFlag(false, "wechat_connect.mobile_enabled");
        String baseMode = firstNonBlank(
                environment.getProperty("wechat_connect.mode"),
                environment.getProperty("WECHAT_CONNECT_MODE"),
                DEFAULT_WECHAT_MODE
        );

        String legacyAppId = firstNonBlank(
                settings.get(KEY_WECHAT_CONNECT_APP_ID),
                baseAppId,
                baseOpenAppId,
                baseMpAppId,
                baseMobileAppId
        );
        String legacyAppSecret = firstNonBlank(
                settings.get(KEY_WECHAT_CONNECT_APP_SECRET),
                baseAppSecret,
                baseOpenAppSecret,
                baseMpAppSecret,
                baseMobileAppSecret
        );
        String openAppId = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_OPEN_APP_ID), baseOpenAppId, legacyAppId);
        String openAppSecret = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_OPEN_APP_SECRET), baseOpenAppSecret, legacyAppSecret);
        String mpAppId = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_MP_APP_ID), baseMpAppId, legacyAppId);
        String mpAppSecret = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_MP_APP_SECRET), baseMpAppSecret, legacyAppSecret);
        String mobileAppId = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_MOBILE_APP_ID), baseMobileAppId, legacyAppId);
        String mobileAppSecret = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_MOBILE_APP_SECRET), baseMobileAppSecret, legacyAppSecret);

        String modeRaw = firstNonBlank(settings.get(KEY_WECHAT_CONNECT_MODE), baseMode, DEFAULT_WECHAT_MODE);
        WeChatModeFlags modeFlags = mergeWeChatModeFlags(settings, baseEnabled, baseOpenEnabled, baseMpEnabled, baseMobileEnabled, modeRaw);

        if (!baseEnabled) {
            return new WeChatCapabilities(false, false, false, false);
        }

        boolean openReady = modeFlags.openEnabled() && !openAppId.isBlank() && !openAppSecret.isBlank();
        boolean mpReady = modeFlags.mpEnabled() && !mpAppId.isBlank() && !mpAppSecret.isBlank();
        boolean mobileReady = modeFlags.mobileEnabled() && !mobileAppId.isBlank() && !mobileAppSecret.isBlank();
        return new WeChatCapabilities(openReady || mpReady, openReady, mpReady, mobileReady);
    }

    private WeChatModeFlags mergeWeChatModeFlags(
            Map<String, String> settings,
            boolean enabled,
            boolean baseOpenEnabled,
            boolean baseMpEnabled,
            boolean baseMobileEnabled,
            String modeRaw
    ) {
        String normalizedMode = normalizeWeChatMode(modeRaw);
        String rawOpen = settings.get(KEY_WECHAT_CONNECT_OPEN_ENABLED);
        String rawMp = settings.get(KEY_WECHAT_CONNECT_MP_ENABLED);
        String rawMobile = settings.get(KEY_WECHAT_CONNECT_MOBILE_ENABLED);
        boolean openConfigured = rawOpen != null && !rawOpen.trim().isEmpty();
        boolean mpConfigured = rawMp != null && !rawMp.trim().isEmpty();
        boolean mobileConfigured = rawMobile != null && !rawMobile.trim().isEmpty();

        if (openConfigured || mpConfigured || mobileConfigured) {
            boolean openEnabled = isStrictTrue(rawOpen);
            boolean mpEnabled = isStrictTrue(rawMp);
            boolean mobileEnabled = isStrictTrue(rawMobile);
            if (!settings.containsKey(KEY_WECHAT_CONNECT_ENABLED)
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
        return switch (normalizedMode) {
            case "mp" -> new WeChatModeFlags(false, true, false);
            case "mobile" -> new WeChatModeFlags(false, false, true);
            default -> new WeChatModeFlags(true, false, false);
        };
    }

    private boolean resolveEnvironmentFlag(boolean defaultValue, String propertyKey) {
        String value = firstNonBlank(
                environment.getProperty(propertyKey),
                environment.getProperty(propertyKey.toUpperCase().replace('.', '_'))
        );
        if (value.isBlank()) {
            return defaultValue;
        }
        return isStrictTrue(value);
    }

    private String resolveVersion() {
        return firstNonBlank(
                environment.getProperty("api-private-router.public-settings.version"),
                environment.getProperty("API_PRIVATE_ROUTER_PUBLIC_SETTINGS_VERSION"),
                environment.getProperty("api-private-router.version"),
                environment.getProperty("API_PRIVATE_ROUTER_VERSION"),
                environment.getProperty("app.version"),
                environment.getProperty("APP_VERSION"),
                PublicSettingsService.class.getPackage().getImplementationVersion(),
                DEFAULT_VERSION
        );
    }

    private List<CustomMenuItem> parseUserVisibleMenuItems(String raw) {
        List<CustomMenuItem> items = jsonHelper.readList(raw, CustomMenuItem.class);
        List<CustomMenuItem> filtered = new ArrayList<>();
        for (CustomMenuItem item : items) {
            if (item == null || "admin".equals(item.visibility())) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    private List<CustomEndpoint> parseCustomEndpoints(String raw) {
        return jsonHelper.readList(raw, CustomEndpoint.class);
    }

    private List<String> parseRegistrationEmailSuffixWhitelist(String raw) {
        List<String> items = jsonHelper.readStringList(raw);
        if (items.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : items) {
            String suffix = normalizeRegistrationEmailSuffix(item);
            if (suffix != null) {
                normalized.add(suffix);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private String normalizeRegistrationEmailSuffix(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if (value.isEmpty()) {
            return null;
        }
        String domain = value;
        if (value.contains("@")) {
            if (!value.startsWith("@") || value.indexOf('@') != value.lastIndexOf('@')) {
                return null;
            }
            domain = value.substring(1);
        }
        if (domain.isEmpty() || domain.contains("@") || !EMAIL_DOMAIN_PATTERN.matcher(domain).matches()) {
            return null;
        }
        return "@" + domain;
    }

    private int parseTableDefaultPageSize(String raw) {
        int defaultPageSize = DEFAULT_TABLE_PAGE_SIZE;
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                defaultPageSize = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                defaultPageSize = DEFAULT_TABLE_PAGE_SIZE;
            }
        }
        if (defaultPageSize < 5 || defaultPageSize > 1000) {
            return DEFAULT_TABLE_PAGE_SIZE;
        }
        return defaultPageSize;
    }

    private List<Integer> parseTablePageSizeOptions(String raw) {
        List<Integer> parsed = jsonHelper.readList(raw, Integer.class);
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (Integer value : parsed) {
            if (value == null || value < 5 || value > 1000) {
                continue;
            }
            values.add(value);
        }
        if (values.isEmpty()) {
            return DEFAULT_TABLE_PAGE_OPTIONS;
        }
        List<Integer> normalized = new ArrayList<>(values);
        normalized.sort(Integer::compareTo);
        return List.copyOf(normalized);
    }

    private double parseNonNegativeDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return value >= 0 ? value : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int parseChannelMonitorInterval(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return CHANNEL_MONITOR_INTERVAL_FALLBACK;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                return 0;
            }
            if (value < CHANNEL_MONITOR_INTERVAL_MIN) {
                return CHANNEL_MONITOR_INTERVAL_MIN;
            }
            if (value > CHANNEL_MONITOR_INTERVAL_MAX) {
                return CHANNEL_MONITOR_INTERVAL_MAX;
            }
            return value;
        } catch (NumberFormatException ignored) {
            return CHANNEL_MONITOR_INTERVAL_FALLBACK;
        }
    }

    private boolean isStrictTrue(String raw) {
        return "true".equals(trimmedLower(raw));
    }

    private boolean isFalseSettingValue(String raw) {
        return switch (trimmedLower(raw)) {
            case "false", "0", "off", "disabled" -> true;
            default -> false;
        };
    }

    private String trimmedLower(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
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

    private String normalizeWeChatMode(String mode) {
        return switch (trimmedLower(mode)) {
            case "mp" -> "mp";
            case "mobile" -> "mobile";
            default -> DEFAULT_WECHAT_MODE;
        };
    }

    private record WeChatModeFlags(boolean openEnabled, boolean mpEnabled, boolean mobileEnabled) {
    }

    private record WeChatCapabilities(boolean webEnabled, boolean openEnabled, boolean mpEnabled, boolean mobileEnabled) {
    }
}
