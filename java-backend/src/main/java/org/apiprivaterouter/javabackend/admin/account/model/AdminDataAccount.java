package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.Map;

public record AdminDataAccount(
        String name,
        String notes,
        String platform,
        String type,
        Map<String, Object> credentials,
        Map<String, Object> extra,
        String proxy_key,
        int concurrency,
        int priority,
        Double rate_multiplier,
        Long expires_at,
        Boolean auto_pause_on_expired
) {
}
