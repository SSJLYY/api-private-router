package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record FundTransactionResponse(
        long id,
        String tx_type,
        String direction,
        BigDecimal amount,
        BigDecimal balance_before,
        BigDecimal balance_after,
        String ref_type,
        Long ref_id,
        Long related_user_id,
        BigDecimal fee,
        String group_id,
        String remark,
        String description,
        String created_at
) {}
