package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GatewayOpenAiFastPolicyService {

    private static final String SETTINGS_KEY_OPENAI_FAST_POLICY_SETTINGS = "openai_fast_policy_settings";
    private static final String ACTION_PASS = "pass";
    private static final String ACTION_FILTER = "filter";
    private static final String ACTION_BLOCK = "block";
    private static final String SCOPE_ALL = "all";
    private static final String SCOPE_OAUTH = "oauth";
    private static final String SCOPE_APIKEY = "apikey";
    private static final String SCOPE_BEDROCK = "bedrock";
    private static final String TIER_ANY = "all";
    private static final String TIER_PRIORITY = "priority";

    private final AdminSettingsRepository settingsRepository;
    private final JsonHelper jsonHelper;

    public GatewayOpenAiFastPolicyService(
            AdminSettingsRepository settingsRepository,
            JsonHelper jsonHelper
    ) {
        this.settingsRepository = settingsRepository;
        this.jsonHelper = jsonHelper;
    }

    public FastPolicyApplyResult applyToRequestBody(
            AdminAccountResponse account,
            String model,
            ObjectNode requestBody,
            String normalizedServiceTier
    ) {
        if (requestBody == null || normalizedServiceTier == null || normalizedServiceTier.isBlank()) {
            return FastPolicyApplyResult.pass(requestBody, normalizedServiceTier);
        }
        RuleDecision decision = evaluate(account, model, normalizedServiceTier);
        return switch (decision.action()) {
            case ACTION_BLOCK -> FastPolicyApplyResult.block(requestBody, normalizedServiceTier, blockMessage(decision, normalizedServiceTier, model));
            case ACTION_FILTER -> {
                ObjectNode mutated = requestBody.deepCopy();
                mutated.remove("service_tier");
                yield FastPolicyApplyResult.filter(mutated);
            }
            default -> FastPolicyApplyResult.pass(requestBody, normalizedServiceTier);
        };
    }

    public FastPolicyApplyResult applyToResponseCreateFrame(
            AdminAccountResponse account,
            String model,
            ObjectNode payload
    ) {
        if (payload == null) {
            return FastPolicyApplyResult.pass(null, null);
        }
        String eventType = text(payload.get("type"));
        if (!"response.create".equals(eventType)) {
            return FastPolicyApplyResult.pass(payload, null);
        }
        String normalizedServiceTier = normalizeServiceTier(text(payload.get("service_tier")));
        if (normalizedServiceTier == null) {
            return FastPolicyApplyResult.pass(payload, null);
        }
        RuleDecision decision = evaluate(account, model, normalizedServiceTier);
        return switch (decision.action()) {
            case ACTION_BLOCK -> FastPolicyApplyResult.block(payload, normalizedServiceTier, blockMessage(decision, normalizedServiceTier, model));
            case ACTION_FILTER -> {
                ObjectNode mutated = payload.deepCopy();
                mutated.remove("service_tier");
                yield FastPolicyApplyResult.filter(mutated);
            }
            default -> {
                ObjectNode mutated = payload;
                if (!normalizedServiceTier.equals(text(payload.get("service_tier")))) {
                    mutated = payload.deepCopy();
                    mutated.put("service_tier", normalizedServiceTier);
                }
                yield FastPolicyApplyResult.pass(mutated, normalizedServiceTier);
            }
        };
    }

    public String normalizeServiceTier(String rawTier) {
        if (rawTier == null) {
            return null;
        }
        String normalized = rawTier.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        if ("fast".equals(normalized)) {
            return TIER_PRIORITY;
        }
        return switch (normalized) {
            case "priority", "flex", "auto", "default", "scale" -> normalized;
            default -> null;
        };
    }

    private RuleDecision evaluate(AdminAccountResponse account, String model, String serviceTier) {
        OpenAiFastPolicySettings settings = loadSettings();
        if (settings == null || settings.rules() == null || settings.rules().isEmpty()) {
            settings = defaultSettings();
        }
        boolean isOAuth = isOauthLikeType(account == null ? null : account.type());
        boolean isBedrock = account != null
                && "anthropic".equalsIgnoreCase(account.platform())
                && "bedrock".equalsIgnoreCase(account.type());
        String tier = serviceTier == null ? "" : serviceTier.trim().toLowerCase(Locale.ROOT);
        for (OpenAiFastPolicyRule rule : settings.rules()) {
            if (rule == null || !scopeMatches(rule.scope(), isOAuth, isBedrock)) {
                continue;
            }
            String ruleTier = normalizeTierRule(rule.service_tier());
            if (!ruleTier.isEmpty() && !TIER_ANY.equals(ruleTier) && !ruleTier.equals(tier)) {
                continue;
            }
            return resolveRuleAction(rule, model);
        }
        return new RuleDecision(ACTION_PASS, "");
    }

    private RuleDecision resolveRuleAction(OpenAiFastPolicyRule rule, String model) {
        List<String> whitelist = rule.model_whitelist() == null ? List.of() : rule.model_whitelist();
        if (whitelist.isEmpty()) {
            return new RuleDecision(normalizeAction(rule.action()), defaultString(rule.error_message()));
        }
        if (matchModelWhitelist(defaultString(model), whitelist)) {
            return new RuleDecision(normalizeAction(rule.action()), defaultString(rule.error_message()));
        }
        String fallbackAction = normalizeAction(rule.fallback_action());
        if (!fallbackAction.isEmpty()) {
            return new RuleDecision(fallbackAction, defaultString(rule.fallback_error_message()));
        }
        return new RuleDecision(ACTION_PASS, "");
    }

    private boolean scopeMatches(String scope, boolean isOAuth, boolean isBedrock) {
        String normalized = defaultString(scope).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case SCOPE_ALL, "" -> true;
            case SCOPE_OAUTH -> isOAuth;
            case SCOPE_APIKEY -> !isOAuth && !isBedrock;
            case SCOPE_BEDROCK -> isBedrock;
            default -> true;
        };
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private boolean matchModelWhitelist(String model, List<String> whitelist) {
        for (String pattern : whitelist) {
            if (matchModelPattern(pattern, model)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchModelPattern(String pattern, String model) {
        String normalizedPattern = defaultString(pattern);
        String normalizedModel = defaultString(model);
        if (normalizedPattern.equals(normalizedModel)) {
            return true;
        }
        if (normalizedPattern.endsWith("*")) {
            String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
            return normalizedModel.startsWith(prefix);
        }
        return false;
    }

    private String normalizeTierRule(String tier) {
        String normalized = defaultString(tier).trim().toLowerCase(Locale.ROOT);
        if ("fast".equals(normalized)) {
            return TIER_PRIORITY;
        }
        return normalized;
    }

    private String normalizeAction(String action) {
        String normalized = defaultString(action).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case ACTION_PASS, ACTION_FILTER, ACTION_BLOCK -> normalized;
            default -> "";
        };
    }

    private String blockMessage(RuleDecision decision, String serviceTier, String model) {
        if (decision.errorMessage() != null && !decision.errorMessage().isBlank()) {
            return decision.errorMessage().trim();
        }
        return "openai service_tier=" + serviceTier + " is not allowed for model " + defaultString(model);
    }

    private OpenAiFastPolicySettings loadSettings() {
        if (settingsRepository == null || jsonHelper == null) {
            return defaultSettings();
        }
        String raw = settingsRepository.getSettingValue(SETTINGS_KEY_OPENAI_FAST_POLICY_SETTINGS);
        OpenAiFastPolicySettings parsed = jsonHelper.readObject(raw, OpenAiFastPolicySettings.class);
        return parsed == null ? defaultSettings() : parsed;
    }

    private OpenAiFastPolicySettings defaultSettings() {
        return new OpenAiFastPolicySettings(List.of(
                new OpenAiFastPolicyRule(
                        TIER_PRIORITY,
                        ACTION_FILTER,
                        SCOPE_ALL,
                        "",
                        List.of(),
                        ACTION_PASS,
                        ""
                )
        ));
    }

    private String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return "";
        }
        return node.asText().trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    public record FastPolicyApplyResult(
            Action action,
            ObjectNode payload,
            String normalizedServiceTier,
            String blockMessage
    ) {
        public static FastPolicyApplyResult pass(ObjectNode payload, String normalizedServiceTier) {
            return new FastPolicyApplyResult(Action.PASS, payload, normalizedServiceTier, null);
        }

        public static FastPolicyApplyResult filter(ObjectNode payload) {
            return new FastPolicyApplyResult(Action.FILTER, payload, null, null);
        }

        public static FastPolicyApplyResult block(ObjectNode payload, String normalizedServiceTier, String blockMessage) {
            return new FastPolicyApplyResult(Action.BLOCK, payload, normalizedServiceTier, blockMessage);
        }
    }

    public enum Action {
        PASS,
        FILTER,
        BLOCK
    }

    private record RuleDecision(String action, String errorMessage) {
    }

    private record OpenAiFastPolicySettings(List<OpenAiFastPolicyRule> rules) {
    }

    private record OpenAiFastPolicyRule(
            String service_tier,
            String action,
            String scope,
            String error_message,
            List<String> model_whitelist,
            String fallback_action,
            String fallback_error_message
    ) {
    }
}
