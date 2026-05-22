package org.apiprivaterouter.javabackend.admin.affiliate.model;

import java.util.List;

public record BatchSetRateRequest(
        List<Long> user_ids,
        Double aff_rebate_rate_percent,
        Boolean clear
) {
}
