package org.apiprivaterouter.javabackend.admin.settings.model;

import java.util.List;

public record RectifierSettingsResponse(
        boolean enabled,
        boolean thinking_signature_enabled,
        boolean thinking_budget_enabled,
        boolean apikey_signature_enabled,
        List<String> apikey_signature_patterns
) {
}
