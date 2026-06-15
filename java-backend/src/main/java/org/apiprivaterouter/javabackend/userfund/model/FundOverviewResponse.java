package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record FundOverviewResponse(
        long total_users,
        BigDecimal total_balance,
        BigDecimal total_frozen,
        BigDecimal total_credit_limit,
        BigDecimal total_credit_used,
        BigDecimal total_outstanding_loan,
        BigDecimal total_outstanding_lend,
        long active_freeze_count,
        long active_loan_count,
        long active_lend_count,
        long today_recharge_count,
        BigDecimal today_recharge_amount,
        long today_transfer_count,
        BigDecimal today_transfer_amount,
        long today_deduct_count,
        BigDecimal today_deduct_amount
) {}
