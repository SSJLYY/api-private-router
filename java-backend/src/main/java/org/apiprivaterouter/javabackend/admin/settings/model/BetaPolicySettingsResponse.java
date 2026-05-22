package org.apiprivaterouter.javabackend.admin.settings.model;

import java.util.List;

public record BetaPolicySettingsResponse(
        List<BetaPolicyRule> rules
) {
    public record BetaPolicyRule(
            String beta_token,
            String action,
            String scope,
            String error_message,
            List<String> model_whitelist,
            String fallback_action,
            String fallback_error_message
    ) {
    }
}
