package org.apiprivaterouter.javabackend.gateway.runtime.service;

import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class GatewayOpenAiAccountRoutingPolicy {

    private static final String MODE_OFF = "off";
    private static final String MODE_CTX_POOL = "ctx_pool";
    private static final String MODE_SHARED = "shared";
    private static final String MODE_DEDICATED = "dedicated";
    private static final String MODE_PASSTHROUGH = "passthrough";

    public boolean cannotHandleResponsesHttp(GatewayRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            return true;
        }
        GatewayAccountSummary account = runtimeContext.account();
        if (!"openai".equalsIgnoreCase(account.platform())) {
            return true;
        }
        if (!"apikey".equalsIgnoreCase(account.type())
                && !"oauth".equalsIgnoreCase(account.type())
                && !"setup-token".equalsIgnoreCase(account.type())) {
            return true;
        }
        return false;
    }

    public boolean canHandleResponsesHttp(GatewayRuntimeContext runtimeContext) {
        return !cannotHandleResponsesHttp(runtimeContext);
    }

    public boolean canHandleResponsesWebSocket(GatewayRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            return false;
        }
        if (!canHandleResponsesHttp(runtimeContext)) {
            return false;
        }
        GatewayAccountSummary account = runtimeContext.account();
        if (isWsForceHttpEnabled(account)) {
            return false;
        }
        if (!isResponsesWebSocketEnabled(account)) {
            return false;
        }
        String mode = resolveResponsesWebSocketMode(account);
        return MODE_CTX_POOL.equals(mode)
                || MODE_SHARED.equals(mode)
                || MODE_DEDICATED.equals(mode)
                || MODE_PASSTHROUGH.equals(mode);
    }

    public boolean isPassthroughEnabled(GatewayAccountSummary account) {
        if (account == null || account.extra() == null) {
            return false;
        }
        return isTrue(account.extra().get("openai_passthrough"))
                || isTrue(account.extra().get("openai_oauth_passthrough"));
    }

    private boolean isWsForceHttpEnabled(GatewayAccountSummary account) {
        return account != null
                && account.extra() != null
                && isTrue(account.extra().get("openai_ws_force_http"));
    }

    private boolean isResponsesWebSocketEnabled(GatewayAccountSummary account) {
        if (account == null || account.extra() == null) {
            return false;
        }
        Map<String, Object> extra = account.extra();
        String mode = resolveResponsesWebSocketMode(account);
        if (!mode.isEmpty() && !MODE_OFF.equals(mode)) {
            return true;
        }
        if (isOauthLikeType(account.type())
                && extra.get("openai_oauth_responses_websockets_v2_enabled") instanceof Boolean enabled) {
            return enabled;
        }
        if (extra.get("openai_apikey_responses_websockets_v2_enabled") instanceof Boolean enabled) {
            return enabled;
        }
        if (extra.get("responses_websockets_v2_enabled") instanceof Boolean enabled) {
            return enabled;
        }
        if (extra.get("openai_ws_enabled") instanceof Boolean enabled) {
            return enabled;
        }
        return false;
    }

    private String resolveResponsesWebSocketMode(GatewayAccountSummary account) {
        if (account == null || account.extra() == null) {
            return MODE_CTX_POOL;
        }
        Map<String, Object> extra = account.extra();
        if (isOauthLikeType(account.type())) {
            String oauthMode = normalizeMode(extra.get("openai_oauth_responses_websockets_v2_mode"));
            if (!oauthMode.isEmpty()) {
                return oauthMode;
            }
            if (extra.get("openai_oauth_responses_websockets_v2_enabled") instanceof Boolean enabled) {
                return enabled ? MODE_CTX_POOL : MODE_OFF;
            }
        }
        if ("apikey".equalsIgnoreCase(account.type())) {
            String apiKeyMode = normalizeMode(extra.get("openai_apikey_responses_websockets_v2_mode"));
            if (!apiKeyMode.isEmpty()) {
                return apiKeyMode;
            }
            if (extra.get("openai_apikey_responses_websockets_v2_enabled") instanceof Boolean enabled) {
                return enabled ? MODE_CTX_POOL : MODE_OFF;
            }
        }
        String mode = normalizeMode(extra.get("responses_websockets_v2_mode"));
        if (!mode.isEmpty()) {
            return mode;
        }
        if (extra.get("responses_websockets_v2_enabled") instanceof Boolean enabled) {
            return enabled ? MODE_CTX_POOL : MODE_OFF;
        }
        if (extra.get("openai_ws_enabled") instanceof Boolean enabled) {
            return enabled ? MODE_CTX_POOL : MODE_OFF;
        }
        return MODE_CTX_POOL;
    }

    private String normalizeMode(Object raw) {
        if (!(raw instanceof String value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case MODE_OFF, MODE_CTX_POOL, MODE_SHARED, MODE_DEDICATED, MODE_PASSTHROUGH -> normalized;
            default -> "";
        };
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text.trim());
        }
        return false;
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }
}
