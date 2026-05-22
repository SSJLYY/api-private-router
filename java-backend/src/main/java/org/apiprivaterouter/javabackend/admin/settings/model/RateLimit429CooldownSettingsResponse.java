package org.apiprivaterouter.javabackend.admin.settings.model;

public record RateLimit429CooldownSettingsResponse(
        boolean enabled,
        int cooldown_seconds
) {
}
