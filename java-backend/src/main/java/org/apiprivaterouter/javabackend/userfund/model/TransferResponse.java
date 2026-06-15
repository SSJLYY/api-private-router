package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record TransferResponse(
        String group_id,
        long from_user_id,
        long to_user_id,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal from_balance_after,
        BigDecimal to_balance_after,
        long from_tx_id,
        long to_tx_id,
        String created_at
) {}
