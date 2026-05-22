package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;
import java.util.Map;

public record BulkUpdateAccountsRequest(
        List<Long> account_ids,
        BulkUpdateAccountFiltersRequest filters,
        String name,
        Long proxy_id,
        Integer concurrency,
        Integer priority,
        Double rate_multiplier,
        Integer load_factor,
        String status,
        Boolean schedulable,
        List<Long> group_ids,
        Map<String, Object> credentials,
        Map<String, Object> extra,
        Boolean confirm_mixed_channel_risk
) {
}
