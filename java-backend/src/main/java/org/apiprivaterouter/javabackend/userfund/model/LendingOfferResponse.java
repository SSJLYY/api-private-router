package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record LendingOfferResponse(
        long id,
        BigDecimal amount,
        BigDecimal interest_rate,
        int duration_days,
        String status,
        String funded_at,
        String created_at
) {}
