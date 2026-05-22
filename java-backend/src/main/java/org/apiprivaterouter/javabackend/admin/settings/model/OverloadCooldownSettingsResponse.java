package org.apiprivaterouter.javabackend.admin.settings.model;

public record OverloadCooldownSettingsResponse(
        boolean enabled,
        int cooldown_minutes
) {
}
