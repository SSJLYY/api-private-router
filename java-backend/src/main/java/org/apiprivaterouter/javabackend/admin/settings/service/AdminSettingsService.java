package org.apiprivaterouter.javabackend.admin.settings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.admin.settings.model.AdminApiKeyStatusResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.BetaPolicySettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.OverloadCooldownSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.RateLimit429CooldownSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.RectifierSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.SendTestEmailRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.StreamTimeoutSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.TestSmtpRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchEmulationConfigResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchEmulationTestRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchProviderConfig;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchTestResult;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchUsageResetRequest;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomEndpoint;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomMenuItem;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AdminSettingsService {

    private static final String KEY_ADMIN_API_KEY = "admin_api_key";
    private static final String KEY_OVERLOAD_COOLDOWN_SETTINGS = "overload_cooldown_settings";
    private static final String KEY_RATE_LIMIT_429_COOLDOWN_SETTINGS = "rate_limit_429_cooldown_settings";
    private static final String KEY_STREAM_TIMEOUT_SETTINGS = "stream_timeout_settings";
    private static final String KEY_RECTIFIER_SETTINGS = "rectifier_settings";
    private static final String KEY_BETA_POLICY_SETTINGS = "beta_policy_settings";
    private static final String KEY_WEB_SEARCH_EMULATION_CONFIG = "web_search_emulation_config";
    private static final String KEY_SMTP_HOST = "smtp_host";
    private static final String KEY_SMTP_PORT = "smtp_port";
    private static final String KEY_SMTP_USERNAME = "smtp_username";
    private static final String KEY_SMTP_PASSWORD = "smtp_password";
    private static final String KEY_SMTP_FROM = "smtp_from";
    private static final String KEY_SMTP_FROM_NAME = "smtp_from_name";
    private static final String KEY_SMTP_USE_TLS = "smtp_use_tls";
    private static final String KEY_TURNSTILE_SECRET_KEY = "turnstile_secret_key";
    private static final String KEY_LINUXDO_CONNECT_CLIENT_SECRET = "linuxdo_connect_client_secret";
    private static final String KEY_WECHAT_CONNECT_APP_SECRET = "wechat_connect_app_secret";
    private static final String KEY_WECHAT_CONNECT_OPEN_APP_SECRET = "wechat_connect_open_app_secret";
    private static final String KEY_WECHAT_CONNECT_MP_APP_SECRET = "wechat_connect_mp_app_secret";
    private static final String KEY_WECHAT_CONNECT_MOBILE_APP_SECRET = "wechat_connect_mobile_app_secret";
    private static final String KEY_OIDC_CONNECT_CLIENT_SECRET = "oidc_connect_client_secret";
    private static final String KEY_GITHUB_OAUTH_CLIENT_SECRET = "github_oauth_client_secret";
    private static final String KEY_GOOGLE_OAUTH_CLIENT_SECRET = "google_oauth_client_secret";
    private static final String KEY_BALANCE_LOW_NOTIFY_ENABLED = "balance_low_notify_enabled";
    private static final String KEY_BALANCE_LOW_NOTIFY_THRESHOLD = "balance_low_notify_threshold";
    private static final String KEY_BALANCE_LOW_NOTIFY_RECHARGE_URL = "balance_low_notify_recharge_url";
    private static final String KEY_ACCOUNT_QUOTA_NOTIFY_ENABLED = "account_quota_notify_enabled";
    private static final String KEY_ACCOUNT_QUOTA_NOTIFY_EMAILS = "account_quota_notify_emails";
    private static final String KEY_TABLE_PAGE_SIZE_OPTIONS = "table_page_size_options";
    private static final String KEY_CUSTOM_MENU_ITEMS = "custom_menu_items";
    private static final String KEY_CUSTOM_ENDPOINTS = "custom_endpoints";
    private static final String KEY_DEFAULT_SUBSCRIPTIONS = "default_subscriptions";
    private static final String KEY_TOTP_ENABLED = "totp_enabled";
    private static final String ENV_TOTP_ENCRYPTION_KEY = "TOTP_ENCRYPTION_KEY";
    private static final String ADMIN_API_KEY_PREFIX = "admin-";
    private static final String DEFAULT_SITE_NAME = "api-private-router";
    private static final String DEFAULT_TEST_QUERY = "search world major events this year";
    private static final int DEFAULT_SMTP_PORT = 587;
    private static final int WEB_SEARCH_MAX_RESULTS = 5;
    private static final int WEB_SEARCH_MAX_PROVIDERS = 10;
    private static final List<String> STREAM_TIMEOUT_ACTIONS = List.of("temp_unsched", "error", "none");
    private static final List<String> BETA_POLICY_ACTIONS = List.of("pass", "filter", "block");
    private static final List<String> BETA_POLICY_SCOPES = List.of("all", "oauth", "apikey", "bedrock");
    private static final List<String> WEB_SEARCH_PROVIDER_TYPES = List.of("brave", "tavily");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final String KEY_REGISTRATION_ENABLED = "registration_enabled";
    private static final String KEY_EMAIL_VERIFY_ENABLED = "email_verify_enabled";
    private static final String KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST = "registration_email_suffix_whitelist";
    private static final String KEY_PROMO_CODE_ENABLED = "promo_code_enabled";
    private static final String KEY_PASSWORD_RESET_ENABLED = "password_reset_enabled";
    private static final String KEY_FRONTEND_URL = "frontend_url";
    private static final String KEY_INVITATION_CODE_ENABLED = "invitation_code_enabled";
    private static final String KEY_DEFAULT_BALANCE = "default_balance";
    private static final String KEY_AFFILIATE_REBATE_RATE = "affiliate_rebate_rate";
    private static final String KEY_AFFILIATE_REBATE_FREEZE_HOURS = "affiliate_rebate_freeze_hours";
    private static final String KEY_AFFILIATE_REBATE_DURATION_DAYS = "affiliate_rebate_duration_days";
    private static final String KEY_AFFILIATE_REBATE_PER_INVITEE_CAP = "affiliate_rebate_per_invitee_cap";
    private static final String KEY_DEFAULT_CONCURRENCY = "default_concurrency";
    private static final String KEY_DEFAULT_USER_RPM_LIMIT = "default_user_rpm_limit";
    private static final String KEY_FORCE_EMAIL_ON_THIRD_PARTY_SIGNUP = "force_email_on_third_party_signup";
    private static final String KEY_SITE_NAME = "site_name";
    private static final String KEY_SITE_LOGO = "site_logo";
    private static final String KEY_SITE_SUBTITLE = "site_subtitle";
    private static final String KEY_API_BASE_URL = "api_base_url";
    private static final String KEY_CONTACT_INFO = "contact_info";
    private static final String KEY_DOC_URL = "doc_url";
    private static final String KEY_HOME_CONTENT = "home_content";
    private static final String KEY_HIDE_CCS_IMPORT_BUTTON = "hide_ccs_import_button";
    private static final String KEY_TABLE_DEFAULT_PAGE_SIZE = "table_default_page_size";
    private static final String KEY_BACKEND_MODE_ENABLED = "backend_mode_enabled";
    private static final String KEY_TURNSTILE_ENABLED = "turnstile_enabled";
    private static final String KEY_TURNSTILE_SITE_KEY = "turnstile_site_key";
    private static final String KEY_LINUXDO_CONNECT_ENABLED = "linuxdo_connect_enabled";
    private static final String KEY_LINUXDO_CONNECT_CLIENT_ID = "linuxdo_connect_client_id";
    private static final String KEY_LINUXDO_CONNECT_REDIRECT_URL = "linuxdo_connect_redirect_url";
    private static final String KEY_WECHAT_CONNECT_ENABLED = "wechat_connect_enabled";
    private static final String KEY_WECHAT_CONNECT_APP_ID = "wechat_connect_app_id";
    private static final String KEY_WECHAT_CONNECT_OPEN_APP_ID = "wechat_connect_open_app_id";
    private static final String KEY_WECHAT_CONNECT_MP_APP_ID = "wechat_connect_mp_app_id";
    private static final String KEY_WECHAT_CONNECT_MOBILE_APP_ID = "wechat_connect_mobile_app_id";
    private static final String KEY_WECHAT_CONNECT_OPEN_ENABLED = "wechat_connect_open_enabled";
    private static final String KEY_WECHAT_CONNECT_MP_ENABLED = "wechat_connect_mp_enabled";
    private static final String KEY_WECHAT_CONNECT_MOBILE_ENABLED = "wechat_connect_mobile_enabled";
    private static final String KEY_WECHAT_CONNECT_MODE = "wechat_connect_mode";
    private static final String KEY_WECHAT_CONNECT_SCOPES = "wechat_connect_scopes";
    private static final String KEY_WECHAT_CONNECT_REDIRECT_URL = "wechat_connect_redirect_url";
    private static final String KEY_WECHAT_CONNECT_FRONTEND_REDIRECT_URL = "wechat_connect_frontend_redirect_url";
    private static final String KEY_OIDC_CONNECT_ENABLED = "oidc_connect_enabled";
    private static final String KEY_OIDC_CONNECT_PROVIDER_NAME = "oidc_connect_provider_name";
    private static final String KEY_OIDC_CONNECT_CLIENT_ID = "oidc_connect_client_id";
    private static final String KEY_OIDC_CONNECT_ISSUER_URL = "oidc_connect_issuer_url";
    private static final String KEY_OIDC_CONNECT_DISCOVERY_URL = "oidc_connect_discovery_url";
    private static final String KEY_OIDC_CONNECT_AUTHORIZE_URL = "oidc_connect_authorize_url";
    private static final String KEY_OIDC_CONNECT_TOKEN_URL = "oidc_connect_token_url";
    private static final String KEY_OIDC_CONNECT_USERINFO_URL = "oidc_connect_userinfo_url";
    private static final String KEY_OIDC_CONNECT_JWKS_URL = "oidc_connect_jwks_url";
    private static final String KEY_OIDC_CONNECT_SCOPES = "oidc_connect_scopes";
    private static final String KEY_OIDC_CONNECT_REDIRECT_URL = "oidc_connect_redirect_url";
    private static final String KEY_OIDC_CONNECT_FRONTEND_REDIRECT_URL = "oidc_connect_frontend_redirect_url";
    private static final String KEY_OIDC_CONNECT_TOKEN_AUTH_METHOD = "oidc_connect_token_auth_method";
    private static final String KEY_OIDC_CONNECT_USE_PKCE = "oidc_connect_use_pkce";
    private static final String KEY_OIDC_CONNECT_VALIDATE_ID_TOKEN = "oidc_connect_validate_id_token";
    private static final String KEY_OIDC_CONNECT_ALLOWED_SIGNING_ALGS = "oidc_connect_allowed_signing_algs";
    private static final String KEY_OIDC_CONNECT_CLOCK_SKEW_SECONDS = "oidc_connect_clock_skew_seconds";
    private static final String KEY_OIDC_CONNECT_REQUIRE_EMAIL_VERIFIED = "oidc_connect_require_email_verified";
    private static final String KEY_OIDC_CONNECT_USERINFO_EMAIL_PATH = "oidc_connect_userinfo_email_path";
    private static final String KEY_OIDC_CONNECT_USERINFO_ID_PATH = "oidc_connect_userinfo_id_path";
    private static final String KEY_OIDC_CONNECT_USERINFO_USERNAME_PATH = "oidc_connect_userinfo_username_path";
    private static final String KEY_GITHUB_OAUTH_ENABLED = "github_oauth_enabled";
    private static final String KEY_GITHUB_OAUTH_CLIENT_ID = "github_oauth_client_id";
    private static final String KEY_GITHUB_OAUTH_REDIRECT_URL = "github_oauth_redirect_url";
    private static final String KEY_GITHUB_OAUTH_FRONTEND_REDIRECT_URL = "github_oauth_frontend_redirect_url";
    private static final String KEY_GOOGLE_OAUTH_ENABLED = "google_oauth_enabled";
    private static final String KEY_GOOGLE_OAUTH_CLIENT_ID = "google_oauth_client_id";
    private static final String KEY_GOOGLE_OAUTH_REDIRECT_URL = "google_oauth_redirect_url";
    private static final String KEY_GOOGLE_OAUTH_FRONTEND_REDIRECT_URL = "google_oauth_frontend_redirect_url";
    private static final String KEY_ENABLE_MODEL_FALLBACK = "enable_model_fallback";
    private static final String KEY_FALLBACK_MODEL_ANTHROPIC = "fallback_model_anthropic";
    private static final String KEY_FALLBACK_MODEL_OPENAI = "fallback_model_openai";
    private static final String KEY_FALLBACK_MODEL_GEMINI = "fallback_model_gemini";
    private static final String KEY_FALLBACK_MODEL_ANTIGRAVITY = "fallback_model_antigravity";
    private static final String KEY_ENABLE_IDENTITY_PATCH = "enable_identity_patch";
    private static final String KEY_IDENTITY_PATCH_PROMPT = "identity_patch_prompt";
    private static final String KEY_OPS_MONITORING_ENABLED = "ops_monitoring_enabled";
    private static final String KEY_OPS_REALTIME_MONITORING_ENABLED = "ops_realtime_monitoring_enabled";
    private static final String KEY_OPS_QUERY_MODE_DEFAULT = "ops_query_mode_default";
    private static final String KEY_OPS_METRICS_INTERVAL_SECONDS = "ops_metrics_interval_seconds";
    private static final String KEY_MIN_CLAUDE_CODE_VERSION = "min_claude_code_version";
    private static final String KEY_MAX_CLAUDE_CODE_VERSION = "max_claude_code_version";
    private static final String KEY_ALLOW_UNGROUPED_KEY_SCHEDULING = "allow_ungrouped_key_scheduling";
    private static final String KEY_ENABLE_FINGERPRINT_UNIFICATION = "enable_fingerprint_unification";
    private static final String KEY_ENABLE_METADATA_PASSTHROUGH = "enable_metadata_passthrough";
    private static final String KEY_ENABLE_CCH_SIGNING = "enable_cch_signing";
    private static final String KEY_ENABLE_ANTHROPIC_CACHE_TTL_1H_INJECTION = "enable_anthropic_cache_ttl_1h_injection";
    private static final String KEY_PAYMENT_ENABLED = "payment_enabled";
    private static final String KEY_RISK_CONTROL_ENABLED = "risk_control_enabled";
    private static final String KEY_PAYMENT_MIN_AMOUNT = "payment_min_amount";
    private static final String KEY_PAYMENT_MAX_AMOUNT = "payment_max_amount";
    private static final String KEY_PAYMENT_DAILY_LIMIT = "payment_daily_limit";
    private static final String KEY_PAYMENT_ORDER_TIMEOUT_MINUTES = "payment_order_timeout_minutes";
    private static final String KEY_PAYMENT_MAX_PENDING_ORDERS = "payment_max_pending_orders";
    private static final String KEY_PAYMENT_ENABLED_TYPES = "payment_enabled_types";
    private static final String KEY_PAYMENT_BALANCE_DISABLED = "payment_balance_disabled";
    private static final String KEY_PAYMENT_BALANCE_RECHARGE_MULTIPLIER = "payment_balance_recharge_multiplier";
    private static final String KEY_PAYMENT_RECHARGE_FEE_RATE = "payment_recharge_fee_rate";
    private static final String KEY_PAYMENT_LOAD_BALANCE_STRATEGY = "payment_load_balance_strategy";
    private static final String KEY_PAYMENT_PRODUCT_NAME_PREFIX = "payment_product_name_prefix";
    private static final String KEY_PAYMENT_PRODUCT_NAME_SUFFIX = "payment_product_name_suffix";
    private static final String KEY_PAYMENT_HELP_IMAGE_URL = "payment_help_image_url";
    private static final String KEY_PAYMENT_HELP_TEXT = "payment_help_text";
    private static final String KEY_PAYMENT_CANCEL_RATE_LIMIT_ENABLED = "payment_cancel_rate_limit_enabled";
    private static final String KEY_PAYMENT_CANCEL_RATE_LIMIT_MAX = "payment_cancel_rate_limit_max";
    private static final String KEY_PAYMENT_CANCEL_RATE_LIMIT_WINDOW = "payment_cancel_rate_limit_window";
    private static final String KEY_PAYMENT_CANCEL_RATE_LIMIT_UNIT = "payment_cancel_rate_limit_unit";
    private static final String KEY_PAYMENT_CANCEL_RATE_LIMIT_WINDOW_MODE = "payment_cancel_rate_limit_window_mode";
    private static final String KEY_PAYMENT_VISIBLE_METHOD_ALIPAY_SOURCE = "payment_visible_method_alipay_source";
    private static final String KEY_PAYMENT_VISIBLE_METHOD_WXPAY_SOURCE = "payment_visible_method_wxpay_source";
    private static final String KEY_PAYMENT_VISIBLE_METHOD_ALIPAY_ENABLED = "payment_visible_method_alipay_enabled";
    private static final String KEY_PAYMENT_VISIBLE_METHOD_WXPAY_ENABLED = "payment_visible_method_wxpay_enabled";
    private static final String KEY_OPENAI_ADVANCED_SCHEDULER_ENABLED = "openai_advanced_scheduler_enabled";
    private static final String KEY_CHANNEL_MONITOR_ENABLED = "channel_monitor_enabled";
    private static final String KEY_CHANNEL_MONITOR_DEFAULT_INTERVAL_SECONDS = "channel_monitor_default_interval_seconds";
    private static final String KEY_AVAILABLE_CHANNELS_ENABLED = "available_channels_enabled";
    private static final String KEY_AFFILIATE_ENABLED = "affiliate_enabled";
    private static final String KEY_OPENAI_FAST_POLICY_SETTINGS = "openai_fast_policy_settings";
    private static final Set<String> JSON_LIST_KEYS = Set.of(
            KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST,
            KEY_TABLE_PAGE_SIZE_OPTIONS,
            KEY_CUSTOM_MENU_ITEMS,
            KEY_CUSTOM_ENDPOINTS,
            KEY_DEFAULT_SUBSCRIPTIONS,
            KEY_ACCOUNT_QUOTA_NOTIFY_EMAILS,
            KEY_PAYMENT_ENABLED_TYPES
    );
    private static final Set<String> SECRET_INPUT_KEYS = Set.of(
            "smtp_password",
            "turnstile_secret_key",
            "linuxdo_connect_client_secret",
            "wechat_connect_app_secret",
            "wechat_connect_open_app_secret",
            "wechat_connect_mp_app_secret",
            "wechat_connect_mobile_app_secret",
            "oidc_connect_client_secret",
            "github_oauth_client_secret",
            "google_oauth_client_secret"
    );
    private static final Set<String> ALL_SETTINGS_KEYS = Set.of(
            KEY_REGISTRATION_ENABLED,
            KEY_EMAIL_VERIFY_ENABLED,
            KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST,
            KEY_PROMO_CODE_ENABLED,
            KEY_PASSWORD_RESET_ENABLED,
            KEY_FRONTEND_URL,
            KEY_INVITATION_CODE_ENABLED,
            KEY_TOTP_ENABLED,
            KEY_DEFAULT_BALANCE,
            KEY_AFFILIATE_REBATE_RATE,
            KEY_AFFILIATE_REBATE_FREEZE_HOURS,
            KEY_AFFILIATE_REBATE_DURATION_DAYS,
            KEY_AFFILIATE_REBATE_PER_INVITEE_CAP,
            KEY_DEFAULT_CONCURRENCY,
            KEY_DEFAULT_USER_RPM_LIMIT,
            KEY_DEFAULT_SUBSCRIPTIONS,
            KEY_FORCE_EMAIL_ON_THIRD_PARTY_SIGNUP,
            KEY_SITE_NAME,
            KEY_SITE_LOGO,
            KEY_SITE_SUBTITLE,
            KEY_API_BASE_URL,
            KEY_CONTACT_INFO,
            KEY_DOC_URL,
            KEY_HOME_CONTENT,
            KEY_HIDE_CCS_IMPORT_BUTTON,
            KEY_TABLE_DEFAULT_PAGE_SIZE,
            KEY_TABLE_PAGE_SIZE_OPTIONS,
            KEY_BACKEND_MODE_ENABLED,
            KEY_CUSTOM_MENU_ITEMS,
            KEY_CUSTOM_ENDPOINTS,
            KEY_SMTP_HOST,
            KEY_SMTP_PORT,
            KEY_SMTP_USERNAME,
            KEY_SMTP_PASSWORD,
            KEY_SMTP_FROM,
            KEY_SMTP_FROM_NAME,
            KEY_SMTP_USE_TLS,
            KEY_TURNSTILE_ENABLED,
            KEY_TURNSTILE_SITE_KEY,
            KEY_TURNSTILE_SECRET_KEY,
            KEY_LINUXDO_CONNECT_ENABLED,
            KEY_LINUXDO_CONNECT_CLIENT_ID,
            KEY_LINUXDO_CONNECT_CLIENT_SECRET,
            KEY_LINUXDO_CONNECT_REDIRECT_URL,
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
            KEY_WECHAT_CONNECT_SCOPES,
            KEY_WECHAT_CONNECT_REDIRECT_URL,
            KEY_WECHAT_CONNECT_FRONTEND_REDIRECT_URL,
            KEY_OIDC_CONNECT_ENABLED,
            KEY_OIDC_CONNECT_PROVIDER_NAME,
            KEY_OIDC_CONNECT_CLIENT_ID,
            KEY_OIDC_CONNECT_CLIENT_SECRET,
            KEY_OIDC_CONNECT_ISSUER_URL,
            KEY_OIDC_CONNECT_DISCOVERY_URL,
            KEY_OIDC_CONNECT_AUTHORIZE_URL,
            KEY_OIDC_CONNECT_TOKEN_URL,
            KEY_OIDC_CONNECT_USERINFO_URL,
            KEY_OIDC_CONNECT_JWKS_URL,
            KEY_OIDC_CONNECT_SCOPES,
            KEY_OIDC_CONNECT_REDIRECT_URL,
            KEY_OIDC_CONNECT_FRONTEND_REDIRECT_URL,
            KEY_OIDC_CONNECT_TOKEN_AUTH_METHOD,
            KEY_OIDC_CONNECT_USE_PKCE,
            KEY_OIDC_CONNECT_VALIDATE_ID_TOKEN,
            KEY_OIDC_CONNECT_ALLOWED_SIGNING_ALGS,
            KEY_OIDC_CONNECT_CLOCK_SKEW_SECONDS,
            KEY_OIDC_CONNECT_REQUIRE_EMAIL_VERIFIED,
            KEY_OIDC_CONNECT_USERINFO_EMAIL_PATH,
            KEY_OIDC_CONNECT_USERINFO_ID_PATH,
            KEY_OIDC_CONNECT_USERINFO_USERNAME_PATH,
            KEY_GITHUB_OAUTH_ENABLED,
            KEY_GITHUB_OAUTH_CLIENT_ID,
            KEY_GITHUB_OAUTH_CLIENT_SECRET,
            KEY_GITHUB_OAUTH_REDIRECT_URL,
            KEY_GITHUB_OAUTH_FRONTEND_REDIRECT_URL,
            KEY_GOOGLE_OAUTH_ENABLED,
            KEY_GOOGLE_OAUTH_CLIENT_ID,
            KEY_GOOGLE_OAUTH_CLIENT_SECRET,
            KEY_GOOGLE_OAUTH_REDIRECT_URL,
            KEY_GOOGLE_OAUTH_FRONTEND_REDIRECT_URL,
            KEY_ENABLE_MODEL_FALLBACK,
            KEY_FALLBACK_MODEL_ANTHROPIC,
            KEY_FALLBACK_MODEL_OPENAI,
            KEY_FALLBACK_MODEL_GEMINI,
            KEY_FALLBACK_MODEL_ANTIGRAVITY,
            KEY_ENABLE_IDENTITY_PATCH,
            KEY_IDENTITY_PATCH_PROMPT,
            KEY_OPS_MONITORING_ENABLED,
            KEY_OPS_REALTIME_MONITORING_ENABLED,
            KEY_OPS_QUERY_MODE_DEFAULT,
            KEY_OPS_METRICS_INTERVAL_SECONDS,
            KEY_MIN_CLAUDE_CODE_VERSION,
            KEY_MAX_CLAUDE_CODE_VERSION,
            KEY_ALLOW_UNGROUPED_KEY_SCHEDULING,
            KEY_ENABLE_FINGERPRINT_UNIFICATION,
            KEY_ENABLE_METADATA_PASSTHROUGH,
            KEY_ENABLE_CCH_SIGNING,
            KEY_ENABLE_ANTHROPIC_CACHE_TTL_1H_INJECTION,
            KEY_PAYMENT_ENABLED,
            KEY_RISK_CONTROL_ENABLED,
            KEY_PAYMENT_MIN_AMOUNT,
            KEY_PAYMENT_MAX_AMOUNT,
            KEY_PAYMENT_DAILY_LIMIT,
            KEY_PAYMENT_ORDER_TIMEOUT_MINUTES,
            KEY_PAYMENT_MAX_PENDING_ORDERS,
            KEY_PAYMENT_ENABLED_TYPES,
            KEY_PAYMENT_BALANCE_DISABLED,
            KEY_PAYMENT_BALANCE_RECHARGE_MULTIPLIER,
            KEY_PAYMENT_RECHARGE_FEE_RATE,
            KEY_PAYMENT_LOAD_BALANCE_STRATEGY,
            KEY_PAYMENT_PRODUCT_NAME_PREFIX,
            KEY_PAYMENT_PRODUCT_NAME_SUFFIX,
            KEY_PAYMENT_HELP_IMAGE_URL,
            KEY_PAYMENT_HELP_TEXT,
            KEY_PAYMENT_CANCEL_RATE_LIMIT_ENABLED,
            KEY_PAYMENT_CANCEL_RATE_LIMIT_MAX,
            KEY_PAYMENT_CANCEL_RATE_LIMIT_WINDOW,
            KEY_PAYMENT_CANCEL_RATE_LIMIT_UNIT,
            KEY_PAYMENT_CANCEL_RATE_LIMIT_WINDOW_MODE,
            KEY_PAYMENT_VISIBLE_METHOD_ALIPAY_SOURCE,
            KEY_PAYMENT_VISIBLE_METHOD_WXPAY_SOURCE,
            KEY_PAYMENT_VISIBLE_METHOD_ALIPAY_ENABLED,
            KEY_PAYMENT_VISIBLE_METHOD_WXPAY_ENABLED,
            KEY_OPENAI_ADVANCED_SCHEDULER_ENABLED,
            KEY_BALANCE_LOW_NOTIFY_ENABLED,
            KEY_BALANCE_LOW_NOTIFY_THRESHOLD,
            KEY_BALANCE_LOW_NOTIFY_RECHARGE_URL,
            KEY_ACCOUNT_QUOTA_NOTIFY_ENABLED,
            KEY_ACCOUNT_QUOTA_NOTIFY_EMAILS,
            KEY_CHANNEL_MONITOR_ENABLED,
            KEY_CHANNEL_MONITOR_DEFAULT_INTERVAL_SECONDS,
            KEY_AVAILABLE_CHANNELS_ENABLED,
            KEY_AFFILIATE_ENABLED,
            KEY_OPENAI_FAST_POLICY_SETTINGS
    );

    private final AdminSettingsRepository repository;
    private final JsonHelper jsonHelper;
    private final PublicSettingsService publicSettingsService;
    private final AdminProxyRepository adminProxyRepository;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public AdminSettingsService(
            AdminSettingsRepository repository,
            JsonHelper jsonHelper,
            PublicSettingsService publicSettingsService,
            AdminProxyRepository adminProxyRepository,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.publicSettingsService = publicSettingsService;
        this.adminProxyRepository = adminProxyRepository;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
    }

    public Map<String, Object> getSettingsOverview() {
        Map<String, String> settings = repository.getSettingValues(List.copyOf(ALL_SETTINGS_KEYS));
        Map<String, Object> response = new LinkedHashMap<>();

        putBoolean(response, "registration_enabled", settings.get(KEY_REGISTRATION_ENABLED), false);
        putBoolean(response, "email_verify_enabled", settings.get(KEY_EMAIL_VERIFY_ENABLED), false);
        response.put("registration_email_suffix_whitelist", jsonHelper.readStringList(settings.get(KEY_REGISTRATION_EMAIL_SUFFIX_WHITELIST)));
        putBoolean(response, "promo_code_enabled", settings.get(KEY_PROMO_CODE_ENABLED), true);
        putBoolean(response, "password_reset_enabled", settings.get(KEY_PASSWORD_RESET_ENABLED), false);
        putString(response, "frontend_url", settings.get(KEY_FRONTEND_URL));
        putBoolean(response, "invitation_code_enabled", settings.get(KEY_INVITATION_CODE_ENABLED), false);
        putBoolean(response, "totp_enabled", settings.get(KEY_TOTP_ENABLED), false);
        response.put("totp_encryption_key_configured", isConfigured(System.getenv(ENV_TOTP_ENCRYPTION_KEY)));
        putDouble(response, "default_balance", settings.get(KEY_DEFAULT_BALANCE), 0D);
        putDouble(response, "affiliate_rebate_rate", settings.get(KEY_AFFILIATE_REBATE_RATE), 0D);
        putInteger(response, "affiliate_rebate_freeze_hours", settings.get(KEY_AFFILIATE_REBATE_FREEZE_HOURS), 0);
        putInteger(response, "affiliate_rebate_duration_days", settings.get(KEY_AFFILIATE_REBATE_DURATION_DAYS), 0);
        putDouble(response, "affiliate_rebate_per_invitee_cap", settings.get(KEY_AFFILIATE_REBATE_PER_INVITEE_CAP), 0D);
        putInteger(response, "default_concurrency", settings.get(KEY_DEFAULT_CONCURRENCY), 5);
        putInteger(response, "default_user_rpm_limit", settings.get(KEY_DEFAULT_USER_RPM_LIMIT), 0);
        response.put("default_subscriptions", jsonHelper.readObjectList(settings.get(KEY_DEFAULT_SUBSCRIPTIONS)));
        appendAuthSourceDefaults(response, settings);
        putBoolean(response, "force_email_on_third_party_signup", settings.get(KEY_FORCE_EMAIL_ON_THIRD_PARTY_SIGNUP), false);

        putString(response, "site_name", firstNonBlank(settings.get(KEY_SITE_NAME), DEFAULT_SITE_NAME));
        putString(response, "site_logo", settings.get(KEY_SITE_LOGO));
        putString(response, "site_subtitle", settings.get(KEY_SITE_SUBTITLE));
        putString(response, "api_base_url", settings.get(KEY_API_BASE_URL));
        putString(response, "contact_info", settings.get(KEY_CONTACT_INFO));
        putString(response, "doc_url", settings.get(KEY_DOC_URL));
        putString(response, "home_content", settings.get(KEY_HOME_CONTENT));
        putBoolean(response, "hide_ccs_import_button", settings.get(KEY_HIDE_CCS_IMPORT_BUTTON), false);
        putInteger(response, "table_default_page_size", settings.get(KEY_TABLE_DEFAULT_PAGE_SIZE), 20);
        response.put("table_page_size_options", parseIntegerList(settings.get(KEY_TABLE_PAGE_SIZE_OPTIONS), List.of(10, 20, 50)));
        putBoolean(response, "backend_mode_enabled", settings.get(KEY_BACKEND_MODE_ENABLED), false);
        response.put("custom_menu_items", jsonHelper.readList(settings.get(KEY_CUSTOM_MENU_ITEMS), CustomMenuItem.class));
        response.put("custom_endpoints", jsonHelper.readList(settings.get(KEY_CUSTOM_ENDPOINTS), CustomEndpoint.class));

        putString(response, "smtp_host", settings.get(KEY_SMTP_HOST));
        putInteger(response, "smtp_port", settings.get(KEY_SMTP_PORT), DEFAULT_SMTP_PORT);
        putString(response, "smtp_username", settings.get(KEY_SMTP_USERNAME));
        response.put("smtp_password_configured", isConfigured(settings.get(KEY_SMTP_PASSWORD)));
        putString(response, "smtp_from_email", settings.get(KEY_SMTP_FROM));
        putString(response, "smtp_from_name", settings.get(KEY_SMTP_FROM_NAME));
        putBoolean(response, "smtp_use_tls", settings.get(KEY_SMTP_USE_TLS), false);

        putBoolean(response, "turnstile_enabled", settings.get(KEY_TURNSTILE_ENABLED), false);
        putString(response, "turnstile_site_key", settings.get(KEY_TURNSTILE_SITE_KEY));
        response.put("turnstile_secret_key_configured", isConfigured(settings.get(KEY_TURNSTILE_SECRET_KEY)));

        putBoolean(response, "linuxdo_connect_enabled", settings.get(KEY_LINUXDO_CONNECT_ENABLED), false);
        putString(response, "linuxdo_connect_client_id", settings.get(KEY_LINUXDO_CONNECT_CLIENT_ID));
        response.put("linuxdo_connect_client_secret_configured", isConfigured(settings.get(KEY_LINUXDO_CONNECT_CLIENT_SECRET)));
        putString(response, "linuxdo_connect_redirect_url", settings.get(KEY_LINUXDO_CONNECT_REDIRECT_URL));

        putBoolean(response, "wechat_connect_enabled", settings.get(KEY_WECHAT_CONNECT_ENABLED), false);
        putString(response, "wechat_connect_app_id", settings.get(KEY_WECHAT_CONNECT_APP_ID));
        response.put("wechat_connect_app_secret_configured", isConfigured(settings.get(KEY_WECHAT_CONNECT_APP_SECRET)));
        putString(response, "wechat_connect_open_app_id", settings.get(KEY_WECHAT_CONNECT_OPEN_APP_ID));
        response.put("wechat_connect_open_app_secret_configured", isConfigured(settings.get(KEY_WECHAT_CONNECT_OPEN_APP_SECRET)));
        putString(response, "wechat_connect_mp_app_id", settings.get(KEY_WECHAT_CONNECT_MP_APP_ID));
        response.put("wechat_connect_mp_app_secret_configured", isConfigured(settings.get(KEY_WECHAT_CONNECT_MP_APP_SECRET)));
        putString(response, "wechat_connect_mobile_app_id", settings.get(KEY_WECHAT_CONNECT_MOBILE_APP_ID));
        response.put("wechat_connect_mobile_app_secret_configured", isConfigured(settings.get(KEY_WECHAT_CONNECT_MOBILE_APP_SECRET)));
        putBoolean(response, "wechat_connect_open_enabled", settings.get(KEY_WECHAT_CONNECT_OPEN_ENABLED), false);
        putBoolean(response, "wechat_connect_mp_enabled", settings.get(KEY_WECHAT_CONNECT_MP_ENABLED), false);
        putBoolean(response, "wechat_connect_mobile_enabled", settings.get(KEY_WECHAT_CONNECT_MOBILE_ENABLED), false);
        putString(response, "wechat_connect_mode", firstNonBlank(settings.get(KEY_WECHAT_CONNECT_MODE), "open"));
        putString(response, "wechat_connect_scopes", settings.get(KEY_WECHAT_CONNECT_SCOPES));
        putString(response, "wechat_connect_redirect_url", settings.get(KEY_WECHAT_CONNECT_REDIRECT_URL));
        putString(response, "wechat_connect_frontend_redirect_url", settings.get(KEY_WECHAT_CONNECT_FRONTEND_REDIRECT_URL));

        putBoolean(response, "oidc_connect_enabled", settings.get(KEY_OIDC_CONNECT_ENABLED), false);
        putString(response, "oidc_connect_provider_name", firstNonBlank(settings.get(KEY_OIDC_CONNECT_PROVIDER_NAME), "OIDC"));
        putString(response, "oidc_connect_client_id", settings.get(KEY_OIDC_CONNECT_CLIENT_ID));
        response.put("oidc_connect_client_secret_configured", isConfigured(settings.get(KEY_OIDC_CONNECT_CLIENT_SECRET)));
        putString(response, "oidc_connect_issuer_url", settings.get(KEY_OIDC_CONNECT_ISSUER_URL));
        putString(response, "oidc_connect_discovery_url", settings.get(KEY_OIDC_CONNECT_DISCOVERY_URL));
        putString(response, "oidc_connect_authorize_url", settings.get(KEY_OIDC_CONNECT_AUTHORIZE_URL));
        putString(response, "oidc_connect_token_url", settings.get(KEY_OIDC_CONNECT_TOKEN_URL));
        putString(response, "oidc_connect_userinfo_url", settings.get(KEY_OIDC_CONNECT_USERINFO_URL));
        putString(response, "oidc_connect_jwks_url", settings.get(KEY_OIDC_CONNECT_JWKS_URL));
        putString(response, "oidc_connect_scopes", settings.get(KEY_OIDC_CONNECT_SCOPES));
        putString(response, "oidc_connect_redirect_url", settings.get(KEY_OIDC_CONNECT_REDIRECT_URL));
        putString(response, "oidc_connect_frontend_redirect_url", settings.get(KEY_OIDC_CONNECT_FRONTEND_REDIRECT_URL));
        putString(response, "oidc_connect_token_auth_method", settings.get(KEY_OIDC_CONNECT_TOKEN_AUTH_METHOD));
        putBoolean(response, "oidc_connect_use_pkce", settings.get(KEY_OIDC_CONNECT_USE_PKCE), false);
        putBoolean(response, "oidc_connect_validate_id_token", settings.get(KEY_OIDC_CONNECT_VALIDATE_ID_TOKEN), false);
        putString(response, "oidc_connect_allowed_signing_algs", settings.get(KEY_OIDC_CONNECT_ALLOWED_SIGNING_ALGS));
        putInteger(response, "oidc_connect_clock_skew_seconds", settings.get(KEY_OIDC_CONNECT_CLOCK_SKEW_SECONDS), 0);
        putBoolean(response, "oidc_connect_require_email_verified", settings.get(KEY_OIDC_CONNECT_REQUIRE_EMAIL_VERIFIED), false);
        putString(response, "oidc_connect_userinfo_email_path", settings.get(KEY_OIDC_CONNECT_USERINFO_EMAIL_PATH));
        putString(response, "oidc_connect_userinfo_id_path", settings.get(KEY_OIDC_CONNECT_USERINFO_ID_PATH));
        putString(response, "oidc_connect_userinfo_username_path", settings.get(KEY_OIDC_CONNECT_USERINFO_USERNAME_PATH));

        putBoolean(response, "github_oauth_enabled", settings.get(KEY_GITHUB_OAUTH_ENABLED), false);
        putString(response, "github_oauth_client_id", settings.get(KEY_GITHUB_OAUTH_CLIENT_ID));
        response.put("github_oauth_client_secret_configured", isConfigured(settings.get(KEY_GITHUB_OAUTH_CLIENT_SECRET)));
        putString(response, "github_oauth_redirect_url", settings.get(KEY_GITHUB_OAUTH_REDIRECT_URL));
        putString(response, "github_oauth_frontend_redirect_url", settings.get(KEY_GITHUB_OAUTH_FRONTEND_REDIRECT_URL));

        putBoolean(response, "google_oauth_enabled", settings.get(KEY_GOOGLE_OAUTH_ENABLED), false);
        putString(response, "google_oauth_client_id", settings.get(KEY_GOOGLE_OAUTH_CLIENT_ID));
        response.put("google_oauth_client_secret_configured", isConfigured(settings.get(KEY_GOOGLE_OAUTH_CLIENT_SECRET)));
        putString(response, "google_oauth_redirect_url", settings.get(KEY_GOOGLE_OAUTH_REDIRECT_URL));
        putString(response, "google_oauth_frontend_redirect_url", settings.get(KEY_GOOGLE_OAUTH_FRONTEND_REDIRECT_URL));

        putBoolean(response, "enable_model_fallback", settings.get(KEY_ENABLE_MODEL_FALLBACK), false);
        putString(response, "fallback_model_anthropic", settings.get(KEY_FALLBACK_MODEL_ANTHROPIC));
        putString(response, "fallback_model_openai", settings.get(KEY_FALLBACK_MODEL_OPENAI));
        putString(response, "fallback_model_gemini", settings.get(KEY_FALLBACK_MODEL_GEMINI));
        putString(response, "fallback_model_antigravity", settings.get(KEY_FALLBACK_MODEL_ANTIGRAVITY));
        putBoolean(response, "enable_identity_patch", settings.get(KEY_ENABLE_IDENTITY_PATCH), false);
        putString(response, "identity_patch_prompt", settings.get(KEY_IDENTITY_PATCH_PROMPT));

        putBoolean(response, "ops_monitoring_enabled", settings.get(KEY_OPS_MONITORING_ENABLED), false);
        putBoolean(response, "ops_realtime_monitoring_enabled", settings.get(KEY_OPS_REALTIME_MONITORING_ENABLED), false);
        putString(response, "ops_query_mode_default", firstNonBlank(settings.get(KEY_OPS_QUERY_MODE_DEFAULT), "auto"));
        putInteger(response, "ops_metrics_interval_seconds", settings.get(KEY_OPS_METRICS_INTERVAL_SECONDS), 0);
        putString(response, "min_claude_code_version", settings.get(KEY_MIN_CLAUDE_CODE_VERSION));
        putString(response, "max_claude_code_version", settings.get(KEY_MAX_CLAUDE_CODE_VERSION));
        putBoolean(response, "allow_ungrouped_key_scheduling", settings.get(KEY_ALLOW_UNGROUPED_KEY_SCHEDULING), false);
        putBoolean(response, "enable_fingerprint_unification", settings.get(KEY_ENABLE_FINGERPRINT_UNIFICATION), false);
        putBoolean(response, "enable_metadata_passthrough", settings.get(KEY_ENABLE_METADATA_PASSTHROUGH), false);
        putBoolean(response, "enable_cch_signing", settings.get(KEY_ENABLE_CCH_SIGNING), false);
        putBoolean(response, "enable_anthropic_cache_ttl_1h_injection", settings.get(KEY_ENABLE_ANTHROPIC_CACHE_TTL_1H_INJECTION), false);
        response.put("web_search_emulation_enabled", getWebSearchEmulationConfig().enabled());

        putBoolean(response, "payment_enabled", settings.get(KEY_PAYMENT_ENABLED), false);
        putBoolean(response, "risk_control_enabled", settings.get(KEY_RISK_CONTROL_ENABLED), false);
        putDouble(response, "payment_min_amount", settings.get(KEY_PAYMENT_MIN_AMOUNT), 0D);
        putDouble(response, "payment_max_amount", settings.get(KEY_PAYMENT_MAX_AMOUNT), 0D);
        putDouble(response, "payment_daily_limit", settings.get(KEY_PAYMENT_DAILY_LIMIT), 0D);
        putInteger(response, "payment_order_timeout_minutes", settings.get(KEY_PAYMENT_ORDER_TIMEOUT_MINUTES), 0);
        putInteger(response, "payment_max_pending_orders", settings.get(KEY_PAYMENT_MAX_PENDING_ORDERS), 0);
        response.put("payment_enabled_types", jsonHelper.readStringList(settings.get(KEY_PAYMENT_ENABLED_TYPES)));
        putBoolean(response, "payment_balance_disabled", settings.get(KEY_PAYMENT_BALANCE_DISABLED), false);
        putDouble(response, "payment_balance_recharge_multiplier", settings.get(KEY_PAYMENT_BALANCE_RECHARGE_MULTIPLIER), 0D);
        putDouble(response, "payment_recharge_fee_rate", settings.get(KEY_PAYMENT_RECHARGE_FEE_RATE), 0D);
        putString(response, "payment_load_balance_strategy", settings.get(KEY_PAYMENT_LOAD_BALANCE_STRATEGY));
        putString(response, "payment_product_name_prefix", settings.get(KEY_PAYMENT_PRODUCT_NAME_PREFIX));
        putString(response, "payment_product_name_suffix", settings.get(KEY_PAYMENT_PRODUCT_NAME_SUFFIX));
        putString(response, "payment_help_image_url", settings.get(KEY_PAYMENT_HELP_IMAGE_URL));
        putString(response, "payment_help_text", settings.get(KEY_PAYMENT_HELP_TEXT));
        putBoolean(response, "payment_cancel_rate_limit_enabled", settings.get(KEY_PAYMENT_CANCEL_RATE_LIMIT_ENABLED), false);
        putInteger(response, "payment_cancel_rate_limit_max", settings.get(KEY_PAYMENT_CANCEL_RATE_LIMIT_MAX), 0);
        putInteger(response, "payment_cancel_rate_limit_window", settings.get(KEY_PAYMENT_CANCEL_RATE_LIMIT_WINDOW), 0);
        putString(response, "payment_cancel_rate_limit_unit", settings.get(KEY_PAYMENT_CANCEL_RATE_LIMIT_UNIT));
        putString(response, "payment_cancel_rate_limit_window_mode", settings.get(KEY_PAYMENT_CANCEL_RATE_LIMIT_WINDOW_MODE));
        putString(response, "payment_visible_method_alipay_source", settings.get(KEY_PAYMENT_VISIBLE_METHOD_ALIPAY_SOURCE));
        putString(response, "payment_visible_method_wxpay_source", settings.get(KEY_PAYMENT_VISIBLE_METHOD_WXPAY_SOURCE));
        putBoolean(response, "payment_visible_method_alipay_enabled", settings.get(KEY_PAYMENT_VISIBLE_METHOD_ALIPAY_ENABLED), false);
        putBoolean(response, "payment_visible_method_wxpay_enabled", settings.get(KEY_PAYMENT_VISIBLE_METHOD_WXPAY_ENABLED), false);
        putBoolean(response, "openai_advanced_scheduler_enabled", settings.get(KEY_OPENAI_ADVANCED_SCHEDULER_ENABLED), false);

        putBoolean(response, "balance_low_notify_enabled", settings.get(KEY_BALANCE_LOW_NOTIFY_ENABLED), false);
        putDouble(response, "balance_low_notify_threshold", settings.get(KEY_BALANCE_LOW_NOTIFY_THRESHOLD), 0D);
        putString(response, "balance_low_notify_recharge_url", settings.get(KEY_BALANCE_LOW_NOTIFY_RECHARGE_URL));
        putBoolean(response, "account_quota_notify_enabled", settings.get(KEY_ACCOUNT_QUOTA_NOTIFY_ENABLED), false);
        response.put("account_quota_notify_emails", jsonHelper.readList(settings.get(KEY_ACCOUNT_QUOTA_NOTIFY_EMAILS), NotifyEmailEntry.class));

        putBoolean(response, "channel_monitor_enabled", settings.get(KEY_CHANNEL_MONITOR_ENABLED), true);
        putInteger(response, "channel_monitor_default_interval_seconds", settings.get(KEY_CHANNEL_MONITOR_DEFAULT_INTERVAL_SECONDS), 60);
        putBoolean(response, "available_channels_enabled", settings.get(KEY_AVAILABLE_CHANNELS_ENABLED), false);
        putBoolean(response, "affiliate_enabled", settings.get(KEY_AFFILIATE_ENABLED), false);
        response.put("openai_fast_policy_settings", readObjectOrDefault(settings.get(KEY_OPENAI_FAST_POLICY_SETTINGS), Map.of("rules", List.of())));

        return response;
    }

    public Map<String, Object> updateSettingsOverview(Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("settings cannot be nil");
        }
        Map<String, String> existing = repository.getSettingValues(List.copyOf(ALL_SETTINGS_KEYS));
        Map<String, String> updates = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : request.entrySet()) {
            String apiKey = entry.getKey();
            if (apiKey == null || apiKey.isBlank()) {
                continue;
            }
            String dbKey = mapApiKeyToDbKey(apiKey);
            if (!ALL_SETTINGS_KEYS.contains(dbKey) && !SECRET_INPUT_KEYS.contains(apiKey)) {
                continue;
            }
            Object value = entry.getValue();
            if (SECRET_INPUT_KEYS.contains(apiKey)) {
                String normalized = trimToNull(stringValue(value));
                if (normalized != null) {
                    updates.put(dbKey, normalized);
                }
                continue;
            }
            if (KEY_TOTP_ENABLED.equals(dbKey) && isTruthy(value) && !isConfigured(System.getenv(ENV_TOTP_ENCRYPTION_KEY))) {
                throw new IllegalArgumentException("Cannot enable TOTP: TOTP_ENCRYPTION_KEY environment variable must be configured first.");
            }
            if (JSON_LIST_KEYS.contains(dbKey)) {
                updates.put(dbKey, jsonHelper.writeJson(value == null ? List.of() : value));
                continue;
            }
            String serialized = serializeScalar(dbKey, value, existing);
            if (KEY_API_BASE_URL.equals(dbKey)) {
                serialized = upstreamUrlGuard.normalizePublicApiBaseUrl(serialized);
            }
            updates.put(dbKey, serialized);
        }



        repository.upsertSettingValues(updates);
        return getSettingsOverview();
    }

    public Map<String, String> testSmtpConnection(TestSmtpRequest request) {
        SmtpConfig config = resolveSmtpConfig(
                request == null ? null : request.smtp_host(),
                request == null ? null : request.smtp_port(),
                request == null ? null : request.smtp_username(),
                request == null ? null : request.smtp_password(),
                null,
                null,
                request != null && request.smtp_use_tls()
        );
        verifySmtpConnection(config);
        return Map.of("message", "SMTP connection successful");
    }

    public Map<String, String> sendTestEmail(SendTestEmailRequest request) {
        if (request == null || trimToNull(request.email()) == null || !EMAIL_PATTERN.matcher(request.email().trim()).matches()) {
            throw new IllegalArgumentException("email is invalid");
        }
        SmtpConfig config = resolveSmtpConfig(
                request.smtp_host(),
                request.smtp_port(),
                request.smtp_username(),
                request.smtp_password(),
                request.smtp_from_email(),
                request.smtp_from_name(),
                request.smtp_use_tls()
        );
        sendEmail(
                config,
                request.email().trim(),
                "[" + resolveSiteName() + "] Test Email",
                buildTestEmailBody(resolveSiteName())
        );
        return Map.of("message", "Test email sent successfully");
    }

    public WebSearchEmulationConfigResponse getWebSearchEmulationConfig() {
        return sanitizeWebSearchConfig(getRawWebSearchEmulationConfig());
    }

    public WebSearchEmulationConfigResponse updateWebSearchEmulationConfig(WebSearchEmulationConfigResponse request) {
        WebSearchEmulationConfigResponse normalized = normalizeWebSearchConfig(request);
        repository.upsertSettingValue(KEY_WEB_SEARCH_EMULATION_CONFIG, jsonHelper.writeJson(normalized));
        return sanitizeWebSearchConfig(normalized);
    }

    public Map<String, String> resetWebSearchUsage(WebSearchUsageResetRequest request) {
        if (request == null || trimToNull(request.provider_type()) == null) {
            throw new IllegalArgumentException("provider_type is required");
        }
        String type = request.provider_type().trim().toLowerCase(Locale.ROOT);
        if (!WEB_SEARCH_PROVIDER_TYPES.contains(type)) {
            throw new IllegalArgumentException("provider_type is invalid");
        }
        WebSearchEmulationConfigResponse raw = getRawWebSearchEmulationConfig();
        List<WebSearchProviderConfig> providers = new ArrayList<>();
        boolean matched = false;
        for (WebSearchProviderConfig provider : raw.providers()) {
            if (type.equals(provider.type())) {
                matched = true;
                providers.add(new WebSearchProviderConfig(
                        provider.type(),
                        provider.api_key(),
                        provider.api_key_configured(),
                        provider.quota_limit(),
                        provider.subscribed_at(),
                        0L,
                        provider.proxy_id(),
                        provider.expires_at()
                ));
            } else {
                providers.add(provider);
            }
        }
        if (!matched) {
            throw new IllegalArgumentException("provider_type is not configured");
        }
        repository.upsertSettingValue(KEY_WEB_SEARCH_EMULATION_CONFIG, jsonHelper.writeJson(
                new WebSearchEmulationConfigResponse(raw.enabled(), providers)
        ));
        return Map.of("message", "Usage reset successfully");
    }

    public WebSearchTestResult testWebSearchEmulation(WebSearchEmulationTestRequest request) {
        WebSearchEmulationConfigResponse config = requireEnabledRawWebSearchConfig();
        String query = trimToNull(request == null ? null : request.query());
        if (query == null) {
            query = DEFAULT_TEST_QUERY;
        }
        Exception lastError = null;
        for (WebSearchProviderConfig provider : config.providers()) {
            try {
                return executeWebSearch(provider, query);
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        throw new HttpStatusException(502, lastError == null ? "web search test failed" : lastError.getMessage());
    }

    public OverloadCooldownSettingsResponse getOverloadCooldownSettings() {
        OverloadCooldownSettingsResponse defaults = new OverloadCooldownSettingsResponse(true, 10);
        OverloadCooldownSettingsResponse settings = jsonHelper.readObject(repository.getSettingValue(KEY_OVERLOAD_COOLDOWN_SETTINGS), OverloadCooldownSettingsResponse.class);
        if (settings == null) {
            return defaults;
        }
        return new OverloadCooldownSettingsResponse(
                settings.enabled(),
                Math.max(1, Math.min(settings.cooldown_minutes(), 120))
        );
    }

    public OverloadCooldownSettingsResponse updateOverloadCooldownSettings(OverloadCooldownSettingsResponse request) {
        if (request == null) {
            throw new IllegalArgumentException("settings cannot be nil");
        }
        int minutes = request.cooldown_minutes();
        if (minutes < 1 || minutes > 120) {
            if (request.enabled()) {
                throw new IllegalArgumentException("cooldown_minutes must be between 1-120");
            }
            minutes = 10;
        }
        OverloadCooldownSettingsResponse normalized = new OverloadCooldownSettingsResponse(request.enabled(), minutes);
        repository.upsertSettingValue(KEY_OVERLOAD_COOLDOWN_SETTINGS, jsonHelper.writeJson(normalized));
        return getOverloadCooldownSettings();
    }

    public RateLimit429CooldownSettingsResponse getRateLimit429CooldownSettings() {
        RateLimit429CooldownSettingsResponse defaults = new RateLimit429CooldownSettingsResponse(true, 5);
        RateLimit429CooldownSettingsResponse settings = jsonHelper.readObject(repository.getSettingValue(KEY_RATE_LIMIT_429_COOLDOWN_SETTINGS), RateLimit429CooldownSettingsResponse.class);
        if (settings == null) {
            return defaults;
        }
        return new RateLimit429CooldownSettingsResponse(
                settings.enabled(),
                Math.max(1, Math.min(settings.cooldown_seconds(), 7200))
        );
    }

    public RateLimit429CooldownSettingsResponse updateRateLimit429CooldownSettings(RateLimit429CooldownSettingsResponse request) {
        if (request == null) {
            throw new IllegalArgumentException("settings cannot be nil");
        }
        int seconds = request.cooldown_seconds();
        if (seconds < 1 || seconds > 7200) {
            if (request.enabled()) {
                throw new IllegalArgumentException("cooldown_seconds must be between 1-7200");
            }
            seconds = 5;
        }
        RateLimit429CooldownSettingsResponse normalized = new RateLimit429CooldownSettingsResponse(request.enabled(), seconds);
        repository.upsertSettingValue(KEY_RATE_LIMIT_429_COOLDOWN_SETTINGS, jsonHelper.writeJson(normalized));
        return getRateLimit429CooldownSettings();
    }

    public AdminApiKeyStatusResponse getAdminApiKeyStatus() {
        String key = repository.getSettingValue(KEY_ADMIN_API_KEY);
        if (key == null || key.isBlank()) {
            return new AdminApiKeyStatusResponse(false, "");
        }
        return new AdminApiKeyStatusResponse(true, maskAdminApiKey(key));
    }

    public String regenerateAdminApiKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder suffix = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            suffix.append(String.format("%02x", value));
        }
        String key = ADMIN_API_KEY_PREFIX + suffix;
        repository.upsertSettingValue(KEY_ADMIN_API_KEY, key);
        return key;
    }

    public void deleteAdminApiKey() {
        repository.deleteSetting(KEY_ADMIN_API_KEY);
    }

    public StreamTimeoutSettingsResponse getStreamTimeoutSettings() {
        StreamTimeoutSettingsResponse defaults = new StreamTimeoutSettingsResponse(false, "temp_unsched", 5, 3, 10);
        StreamTimeoutSettingsResponse settings = jsonHelper.readObject(repository.getSettingValue(KEY_STREAM_TIMEOUT_SETTINGS), StreamTimeoutSettingsResponse.class);
        if (settings == null) {
            return defaults;
        }
        String action = STREAM_TIMEOUT_ACTIONS.contains(settings.action()) ? settings.action() : "temp_unsched";
        return new StreamTimeoutSettingsResponse(
                settings.enabled(),
                action,
                Math.max(1, Math.min(settings.temp_unsched_minutes(), 60)),
                Math.max(1, Math.min(settings.threshold_count(), 10)),
                Math.max(1, Math.min(settings.threshold_window_minutes(), 60))
        );
    }

    public StreamTimeoutSettingsResponse updateStreamTimeoutSettings(StreamTimeoutSettingsResponse request) {
        if (request == null) {
            throw new IllegalArgumentException("settings cannot be nil");
        }
        if (request.temp_unsched_minutes() < 1 || request.temp_unsched_minutes() > 60) {
            throw new IllegalArgumentException("temp_unsched_minutes must be between 1-60");
        }
        if (request.threshold_count() < 1 || request.threshold_count() > 10) {
            throw new IllegalArgumentException("threshold_count must be between 1-10");
        }
        if (request.threshold_window_minutes() < 1 || request.threshold_window_minutes() > 60) {
            throw new IllegalArgumentException("threshold_window_minutes must be between 1-60");
        }
        if (!STREAM_TIMEOUT_ACTIONS.contains(request.action())) {
            throw new IllegalArgumentException("invalid action: " + request.action());
        }
        repository.upsertSettingValue(KEY_STREAM_TIMEOUT_SETTINGS, jsonHelper.writeJson(request));
        return getStreamTimeoutSettings();
    }

    public RectifierSettingsResponse getRectifierSettings() {
        RectifierSettingsResponse defaults = new RectifierSettingsResponse(true, true, true, false, List.of());
        RectifierSettingsResponse settings = jsonHelper.readObject(repository.getSettingValue(KEY_RECTIFIER_SETTINGS), RectifierSettingsResponse.class);
        if (settings == null) {
            return defaults;
        }
        return new RectifierSettingsResponse(
                settings.enabled(),
                settings.thinking_signature_enabled(),
                settings.thinking_budget_enabled(),
                settings.apikey_signature_enabled(),
                settings.apikey_signature_patterns() == null ? List.of() : settings.apikey_signature_patterns()
        );
    }

    public RectifierSettingsResponse updateRectifierSettings(RectifierSettingsResponse request) {
        if (request == null) {
            throw new IllegalArgumentException("settings cannot be nil");
        }
        List<String> patterns = request.apikey_signature_patterns() == null ? List.of() : request.apikey_signature_patterns().stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .toList();
        if (patterns.size() > 50) {
            throw new IllegalArgumentException("Too many signature patterns (max 50)");
        }
        for (String pattern : patterns) {
            if (pattern.length() > 500) {
                throw new IllegalArgumentException("Signature pattern too long (max 500 characters)");
            }
        }
        RectifierSettingsResponse normalized = new RectifierSettingsResponse(
                request.enabled(),
                request.thinking_signature_enabled(),
                request.thinking_budget_enabled(),
                request.apikey_signature_enabled(),
                patterns
        );
        repository.upsertSettingValue(KEY_RECTIFIER_SETTINGS, jsonHelper.writeJson(normalized));
        return getRectifierSettings();
    }

    public BetaPolicySettingsResponse getBetaPolicySettings() {
        BetaPolicySettingsResponse defaults = new BetaPolicySettingsResponse(List.of(
                new BetaPolicySettingsResponse.BetaPolicyRule(
                        "fast-mode-2026-02-01",
                        "filter",
                        "all",
                        null,
                        List.of(),
                        null,
                        null
                ),
                new BetaPolicySettingsResponse.BetaPolicyRule(
                        "context-1m-2025-08-07",
                        "filter",
                        "all",
                        null,
                        List.of(),
                        null,
                        null
                )
        ));
        BetaPolicySettingsResponse settings = jsonHelper.readObject(repository.getSettingValue(KEY_BETA_POLICY_SETTINGS), BetaPolicySettingsResponse.class);
        if (settings == null || settings.rules() == null) {
            return defaults;
        }
        return new BetaPolicySettingsResponse(settings.rules());
    }

    public BetaPolicySettingsResponse updateBetaPolicySettings(BetaPolicySettingsResponse request) {
        if (request == null) {
            throw new IllegalArgumentException("settings cannot be nil");
        }
        List<BetaPolicySettingsResponse.BetaPolicyRule> rules = request.rules() == null ? List.of() : request.rules();
        for (int i = 0; i < rules.size(); i++) {
            BetaPolicySettingsResponse.BetaPolicyRule rule = rules.get(i);
            if (rule.beta_token() == null || rule.beta_token().trim().isEmpty()) {
                throw new IllegalArgumentException("rule[%d]: beta_token cannot be empty".formatted(i));
            }
            if (!BETA_POLICY_ACTIONS.contains(rule.action())) {
                throw new IllegalArgumentException("rule[%d]: invalid action %s".formatted(i, rule.action()));
            }
            if (!BETA_POLICY_SCOPES.contains(rule.scope())) {
                throw new IllegalArgumentException("rule[%d]: invalid scope %s".formatted(i, rule.scope()));
            }
            List<String> whitelist = rule.model_whitelist() == null ? List.of() : rule.model_whitelist();
            for (int j = 0; j < whitelist.size(); j++) {
                String pattern = whitelist.get(j) == null ? "" : whitelist.get(j).trim();
                if (pattern.isEmpty()) {
                    throw new IllegalArgumentException("rule[%d]: model_whitelist[%d] cannot be empty".formatted(i, j));
                }
            }
            if (rule.fallback_action() != null && !rule.fallback_action().isBlank() && !BETA_POLICY_ACTIONS.contains(rule.fallback_action())) {
                throw new IllegalArgumentException("rule[%d]: invalid fallback_action %s".formatted(i, rule.fallback_action()));
            }
        }
        repository.upsertSettingValue(KEY_BETA_POLICY_SETTINGS, jsonHelper.writeJson(request));
        return getBetaPolicySettings();
    }

    private void appendAuthSourceDefaults(Map<String, Object> response, Map<String, String> settings) {
        for (String source : List.of("email", "linuxdo", "oidc", "wechat", "github", "google")) {
            putDouble(response, "auth_source_default_" + source + "_balance", settings.get("auth_source_default_" + source + "_balance"), 0D);
            putInteger(response, "auth_source_default_" + source + "_concurrency", settings.get("auth_source_default_" + source + "_concurrency"), 5);
            response.put("auth_source_default_" + source + "_subscriptions",
                    jsonHelper.readObjectList(settings.get("auth_source_default_" + source + "_subscriptions")));
            putBoolean(response, "auth_source_default_" + source + "_grant_on_signup",
                    settings.get("auth_source_default_" + source + "_grant_on_signup"), false);
            putBoolean(response, "auth_source_default_" + source + "_grant_on_first_bind",
                    settings.get("auth_source_default_" + source + "_grant_on_first_bind"), false);
        }
    }

    private String serializeScalar(String dbKey, Object value, Map<String, String> existing) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        if (value instanceof Number number) {
            return String.valueOf(number);
        }
        String stringValue = stringValue(value);
        if (KEY_SMTP_FROM.equals(dbKey) && trimToNull(stringValue) == null) {
            return defaultString(existing.get(KEY_SMTP_FROM));
        }
        return defaultString(stringValue).trim();
    }

    private String mapApiKeyToDbKey(String apiKey) {
        return switch (apiKey) {
            case "smtp_from_email" -> KEY_SMTP_FROM;
            case "smtp_password" -> KEY_SMTP_PASSWORD;
            case "turnstile_secret_key" -> KEY_TURNSTILE_SECRET_KEY;
            case "linuxdo_connect_client_secret" -> KEY_LINUXDO_CONNECT_CLIENT_SECRET;
            case "wechat_connect_app_secret" -> KEY_WECHAT_CONNECT_APP_SECRET;
            case "wechat_connect_open_app_secret" -> KEY_WECHAT_CONNECT_OPEN_APP_SECRET;
            case "wechat_connect_mp_app_secret" -> KEY_WECHAT_CONNECT_MP_APP_SECRET;
            case "wechat_connect_mobile_app_secret" -> KEY_WECHAT_CONNECT_MOBILE_APP_SECRET;
            case "oidc_connect_client_secret" -> KEY_OIDC_CONNECT_CLIENT_SECRET;
            case "github_oauth_client_secret" -> KEY_GITHUB_OAUTH_CLIENT_SECRET;
            case "google_oauth_client_secret" -> KEY_GOOGLE_OAUTH_CLIENT_SECRET;
            default -> apiKey;
        };
    }

    private SmtpConfig resolveSmtpConfig(
            String host,
            Integer port,
            String username,
            String password,
            String from,
            String fromName,
            boolean useTls
    ) {
        Map<String, String> saved = repository.getSettingValues(List.of(
                KEY_SMTP_HOST, KEY_SMTP_PORT, KEY_SMTP_USERNAME, KEY_SMTP_PASSWORD, KEY_SMTP_FROM, KEY_SMTP_FROM_NAME, KEY_SMTP_USE_TLS
        ));
        String resolvedHost = firstNonBlank(host, saved.get(KEY_SMTP_HOST));
        if (resolvedHost == null) {
            throw new IllegalArgumentException("SMTP host is required");
        }
        int resolvedPort = port != null && port > 0 ? port : parseInt(saved.get(KEY_SMTP_PORT), DEFAULT_SMTP_PORT);
        String resolvedUsername = firstNonBlank(host == null ? null : username, saved.get(KEY_SMTP_USERNAME));
        String resolvedPassword = firstNonBlank(password, saved.get(KEY_SMTP_PASSWORD));
        String resolvedFrom = firstNonBlank(from, saved.get(KEY_SMTP_FROM));
        String resolvedFromName = firstNonBlank(fromName, saved.get(KEY_SMTP_FROM_NAME));
        boolean resolvedUseTls = (host != null || port != null || username != null || password != null || from != null || fromName != null)
                ? useTls
                : parseBoolean(saved.get(KEY_SMTP_USE_TLS), false);
        return new SmtpConfig(resolvedHost, resolvedPort, resolvedUsername, resolvedPassword, resolvedFrom, resolvedFromName, resolvedUseTls);
    }

    private void verifySmtpConnection(SmtpConfig config) {
        JavaMailSenderImpl sender = buildSender(config);
        try {
            MimeMessage message = sender.createMimeMessage();
            message.setSubject("SMTP Connection Test");
            message.setText("SMTP connection test");
            sender.testConnection();
        } catch (MessagingException ex) {
            throw new IllegalArgumentException("SMTP connection test failed: " + ex.getMessage());
        }
    }

    private void sendEmail(SmtpConfig config, String to, String subject, String html) {
        if (trimToNull(config.from()) == null) {
            throw new IllegalArgumentException("SMTP from email is required");
        }
        try {
            JavaMailSenderImpl sender = buildSender(config);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(config.from(), firstNonBlank(config.fromName(), resolveSiteName()));
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (MailAuthenticationException ex) {
            throw new IllegalArgumentException("Failed to send test email: smtp authentication failed");
        } catch (MailSendException ex) {
            throw new IllegalArgumentException("Failed to send test email: smtp send failed");
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            throw new IllegalArgumentException("Failed to send test email: " + ex.getMessage());
        }
    }

    private JavaMailSenderImpl buildSender(SmtpConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setProtocol("smtp");
        if (trimToNull(config.username()) != null) {
            sender.setUsername(config.username());
        }
        if (trimToNull(config.password()) != null) {
            sender.setPassword(config.password());
        }
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", trimToNull(config.username()) != null && trimToNull(config.password()) != null ? "true" : "false");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "20000");
        properties.put("mail.smtp.writetimeout", "20000");
        // useTls=true enables opportunistic+required STARTTLS (the common port 587 case);
        // useTls=false sends plaintext (port 25). Implicit SSL on port 465 is not currently
        // supported — to add it, infer the mode from the configured port rather than reusing
        // the useTls flag, which historically meant "secure transport" and was inverted.
        properties.put("mail.smtp.starttls.enable", config.useTls() ? "true" : "false");
        properties.put("mail.smtp.starttls.required", Boolean.toString(config.useTls()));
        properties.put("mail.smtp.ssl.enable", "false");
        properties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        return sender;
    }

    private String resolveSiteName() {
        String siteName = trimToNull(publicSettingsService.getPublicSettings().site_name());
        return siteName == null ? DEFAULT_SITE_NAME : siteName;
    }

    private String buildTestEmailBody(String siteName) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f5f5f5; margin: 0; padding: 20px; }
                        .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                        .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; }
                        .content { padding: 40px 30px; text-align: center; }
                        .footer { background-color: #f8f9fa; padding: 20px; text-align: center; color: #999; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>%s</h1>
                        </div>
                        <div class="content">
                            <h2>Email Configuration Successful!</h2>
                            <p>This is a test email to verify your SMTP settings are working correctly.</p>
                        </div>
                        <div class="footer">
                            <p>This is an automated test message.</p>
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(siteName);
    }

    private WebSearchEmulationConfigResponse requireEnabledRawWebSearchConfig() {
        WebSearchEmulationConfigResponse config = getRawWebSearchEmulationConfig();
        if (!config.enabled()) {
            throw new IllegalArgumentException("web search emulation is disabled");
        }
        if (config.providers().isEmpty()) {
            throw new IllegalArgumentException("web search emulation providers are not configured");
        }
        return config;
    }

    private WebSearchEmulationConfigResponse normalizeWebSearchConfig(WebSearchEmulationConfigResponse request) {
        if (request == null) {
            return new WebSearchEmulationConfigResponse(false, List.of());
        }
        List<WebSearchProviderConfig> inputProviders = request.providers() == null ? List.of() : request.providers();
        if (inputProviders.size() > WEB_SEARCH_MAX_PROVIDERS) {
            throw new IllegalArgumentException("too many providers (max 10)");
        }
        Map<String, String> existingSecrets = new LinkedHashMap<>();
        for (WebSearchProviderConfig provider : getRawWebSearchEmulationConfig().providers()) {
            if (trimToNull(provider.api_key()) != null) {
                existingSecrets.put(provider.type(), provider.api_key());
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<WebSearchProviderConfig> providers = new ArrayList<>();
        for (int i = 0; i < inputProviders.size(); i++) {
            WebSearchProviderConfig provider = inputProviders.get(i);
            String type = trimToNull(provider.type());
            if (type == null || !WEB_SEARCH_PROVIDER_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("provider[%d]: invalid type".formatted(i));
            }
            type = type.toLowerCase(Locale.ROOT);
            if (!seen.add(type)) {
                throw new IllegalArgumentException("provider[%d]: duplicate type %s".formatted(i, type));
            }
            Long quotaLimit = provider.quota_limit();
            if (quotaLimit != null && quotaLimit < 0) {
                throw new IllegalArgumentException("provider[%d]: quota_limit must be >= 0 or null".formatted(i));
            }
            Long proxyId = provider.proxy_id();
            if (proxyId != null && proxyId <= 0) {
                proxyId = null;
            }
            String apiKey = trimToNull(provider.api_key());
            if (apiKey == null) {
                apiKey = existingSecrets.get(type);
            }
            providers.add(new WebSearchProviderConfig(
                    type,
                    apiKey,
                    apiKey != null,
                    quotaLimit,
                    provider.subscribed_at(),
                    0L,
                    proxyId,
                    provider.expires_at()
            ));
        }
        if (request.enabled()) {
            for (WebSearchProviderConfig provider : providers) {
                if (trimToNull(provider.api_key()) == null) {
                    throw new IllegalArgumentException("provider %s has no api key configured".formatted(provider.type()));
                }
            }
        }
        return new WebSearchEmulationConfigResponse(request.enabled(), providers);
    }

    private WebSearchEmulationConfigResponse sanitizeWebSearchConfig(WebSearchEmulationConfigResponse config) {
        List<WebSearchProviderConfig> providers = new ArrayList<>();
        for (WebSearchProviderConfig provider : config.providers()) {
            providers.add(new WebSearchProviderConfig(
                    provider.type(),
                    "",
                    trimToNull(provider.api_key()) != null || provider.api_key_configured(),
                    provider.quota_limit(),
                    provider.subscribed_at(),
                    provider.quota_used() == null ? 0L : provider.quota_used(),
                    provider.proxy_id(),
                    provider.expires_at()
            ));
        }
        return new WebSearchEmulationConfigResponse(config.enabled(), providers);
    }

    private WebSearchEmulationConfigResponse getRawWebSearchEmulationConfig() {
        WebSearchEmulationConfigResponse config = jsonHelper.readObject(
                repository.getSettingValue(KEY_WEB_SEARCH_EMULATION_CONFIG),
                WebSearchEmulationConfigResponse.class
        );
        if (config == null || config.providers() == null) {
            return new WebSearchEmulationConfigResponse(false, List.of());
        }
        return config;
    }

    private WebSearchTestResult executeWebSearch(WebSearchProviderConfig provider, String query) throws IOException, InterruptedException {
        HttpClient client = buildWebSearchHttpClient(provider.proxy_id());
        return switch (provider.type()) {
            case "brave" -> testBrave(client, provider, query);
            case "tavily" -> testTavily(client, provider, query);
            default -> throw new IllegalArgumentException("unsupported provider: " + provider.type());
        };
    }

    private HttpClient buildWebSearchHttpClient(Long proxyId) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (proxyId != null && proxyId > 0) {
            AdminProxyResponse proxy = adminProxyRepository.getProxy(proxyId)
                    .orElseThrow(() -> new IllegalArgumentException("proxy not found: " + proxyId));
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
        }
        return builder.build();
    }

    private WebSearchTestResult testBrave(HttpClient client, WebSearchProviderConfig provider, String query) throws IOException, InterruptedException {
        String uri = "https://api.search.brave.com/res/v1/web/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + WEB_SEARCH_MAX_RESULTS;
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(20))
                .header("X-Subscription-Token", provider.api_key())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("brave: status " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<WebSearchTestResult.WebSearchResultItem> results = new ArrayList<>();
        JsonNode items = root.path("web").path("results");
        if (items.isArray()) {
            for (JsonNode item : items) {
                results.add(new WebSearchTestResult.WebSearchResultItem(
                        item.path("url").asText(""),
                        item.path("title").asText(""),
                        item.path("description").asText(""),
                        blankToNull(item.path("age").asText(""))
                ));
            }
        }
        return new WebSearchTestResult("brave", results, query);
    }

    private WebSearchTestResult testTavily(HttpClient client, WebSearchProviderConfig provider, String query) throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of(
                "api_key", provider.api_key(),
                "query", query,
                "max_results", WEB_SEARCH_MAX_RESULTS,
                "search_depth", "basic"
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("tavily: status " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<WebSearchTestResult.WebSearchResultItem> results = new ArrayList<>();
        JsonNode items = root.path("results");
        if (items.isArray()) {
            for (JsonNode item : items) {
                results.add(new WebSearchTestResult.WebSearchResultItem(
                        item.path("url").asText(""),
                        item.path("title").asText(""),
                        item.path("content").asText(""),
                        null
                ));
            }
        }
        return new WebSearchTestResult("tavily", results, query);
    }

    private Object readObjectOrDefault(String raw, Object fallback) {
        Map<String, Object> value = jsonHelper.readObjectMap(raw);
        return value.isEmpty() ? fallback : value;
    }

    private List<Integer> parseIntegerList(String raw, List<Integer> fallback) {
        List<String> values = jsonHelper.readStringList(raw);
        if (!values.isEmpty()) {
            List<Integer> result = new ArrayList<>();
            for (String value : values) {
                Integer parsed = tryParseInt(value);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            return result.isEmpty() ? fallback : result;
        }
        List<Map<String, Object>> objects = jsonHelper.readObjectList(raw);
        if (!objects.isEmpty()) {
            return fallback;
        }
        List<Integer> list;
        try {
            list = objectMapper.readerForListOf(Integer.class).readValue(defaultString(raw));
        } catch (IOException ex) {
            list = List.of();
        }
        return list.isEmpty() ? fallback : list;
    }

    private void putString(Map<String, Object> target, String key, String value) {
        target.put(key, defaultString(value));
    }

    private void putBoolean(Map<String, Object> target, String key, String raw, boolean fallback) {
        target.put(key, parseBoolean(raw, fallback));
    }

    private void putInteger(Map<String, Object> target, String key, String raw, int fallback) {
        target.put(key, parseInt(raw, fallback));
    }

    private void putDouble(Map<String, Object> target, String key, String raw, double fallback) {
        target.put(key, parseDouble(raw, fallback));
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        String value = trimToNull(raw);
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value);
    }

    private int parseInt(String raw, int fallback) {
        Integer parsed = tryParseInt(raw);
        return parsed == null ? fallback : parsed;
    }

    private Integer tryParseInt(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double parseDouble(String raw, double fallback) {
        String value = trimToNull(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean isConfigured(String raw) {
        return trimToNull(raw) != null;
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(defaultString(stringValue(value)));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToNull(String value) {
        return trimToNull(value);
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private String maskAdminApiKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= 12) {
            return trimmed;
        }
        return trimmed.substring(0, 10) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private record SmtpConfig(
            String host,
            int port,
            String username,
            String password,
            String from,
            String fromName,
            boolean useTls
    ) {
    }
}
