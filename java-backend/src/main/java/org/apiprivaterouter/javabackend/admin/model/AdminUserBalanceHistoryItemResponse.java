package org.apiprivaterouter.javabackend.admin.model;

import java.util.Map;

public record AdminUserBalanceHistoryItemResponse(
        long id,
        String code,
        String type,
        double value,
        String status,
        Long used_by,
        String used_at,
        String created_at,
        Long group_id,
        int validity_days,
        String notes,
        Map<String, Object> user,
        Map<String, Object> group
) {
}
