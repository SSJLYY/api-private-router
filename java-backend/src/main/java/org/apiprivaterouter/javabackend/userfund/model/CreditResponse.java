package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record CreditResponse(
        long id,
        BigDecimal credit_limit,
        BigDecimal credit_used,
        BigDecimal available_credit,
        BigDecimal interest_rate,
        String status,
        String approved_at,
        String risk_level,
        String next_review_at,
        String remark
) {}
