package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record LendingDetailResponse(
        long id,
        long user_id,
        Long lender_id,
        Long borrower_id,
        BigDecimal amount,
        BigDecimal interest_rate,
        int duration_days,
        BigDecimal platform_fee,
        BigDecimal total_repay_amount,
        BigDecimal remaining_amount,
        String status,
        String funded_at,
        String due_date,
        String repaid_at,
        String cancelled_at,
        String remark,
        String created_at,
        String updated_at
) {}
