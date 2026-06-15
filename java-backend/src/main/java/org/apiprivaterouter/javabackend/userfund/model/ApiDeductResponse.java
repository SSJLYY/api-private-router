package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record ApiDeductResponse(
        long tx_id,
        long user_id,
        BigDecimal amount,
        BigDecimal balance_before,
        BigDecimal balance_after,
        BigDecimal available_after,
        String status,
        String created_at
) {}
