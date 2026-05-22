package org.apiprivaterouter.javabackend.admin.redeem.model;

import java.util.Map;

public record RedeemStatsResponse(
        long total_codes,
        long active_codes,
        long used_codes,
        long expired_codes,
        double total_value_distributed,
        Map<String, Long> by_type
) {
}
