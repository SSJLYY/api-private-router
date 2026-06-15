package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record LoanResponse(
        long id,
        BigDecimal amount,
        BigDecimal interest_rate,
        BigDecimal interest_amount,
        BigDecimal repaid_amount,
        BigDecimal remaining_amount,
        String status,
        String due_date,
        String repaid_at,
        String created_at
) {}
