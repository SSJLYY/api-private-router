package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record FundStatsResponse(
        BigDecimal total_balance,
        BigDecimal total_frozen,
        BigDecimal total_credit_limit,
        BigDecimal total_credit_used,
        BigDecimal available_credit,
        BigDecimal total_loan_amount,
        BigDecimal total_unrepaid
) {}
