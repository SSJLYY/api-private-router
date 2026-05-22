package org.apiprivaterouter.javabackend.admin.group.model;

public record GroupRateMultiplierEntryResponse(
        long user_id,
        String user_name,
        String user_email,
        String user_notes,
        String user_status,
        Double rate_multiplier,
        Integer rpm_override
) {
}
