package org.apiprivaterouter.javabackend.admin.settings.model;

public record StreamTimeoutSettingsResponse(
        boolean enabled,
        String action,
        int temp_unsched_minutes,
        int threshold_count,
        int threshold_window_minutes
) {
}
