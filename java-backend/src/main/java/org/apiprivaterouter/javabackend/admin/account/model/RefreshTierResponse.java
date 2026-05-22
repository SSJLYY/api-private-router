package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.Map;

public record RefreshTierResponse(
        String tier_id,
        Map<String, Object> storage_info,
        Long drive_storage_limit,
        Long drive_storage_usage,
        String updated_at
) {
}
