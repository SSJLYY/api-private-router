package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record FundAccountResponse(
        long id,
        String account_type,
        BigDecimal balance,
        BigDecimal frozen_amount,
        BigDecimal credit_limit,
        BigDecimal credit_used,
        BigDecimal available_balance,
        BigDecimal total_recharged,
        BigDecimal total_consumed,
        BigDecimal total_transferred_in,
        BigDecimal total_transferred_out,
        BigDecimal total_loan_out,
        BigDecimal total_loan_in,
        String status,
        String created_at
) {}
