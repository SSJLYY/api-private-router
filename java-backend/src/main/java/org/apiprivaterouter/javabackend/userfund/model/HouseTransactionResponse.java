package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record HouseTransactionResponse(
        long id,
        String tx_type,
        BigDecimal amount,
        BigDecimal balance_before,
        BigDecimal balance_after,
        String ref_type,
        Long ref_id,
        Long user_id,
        String description,
        String created_at
) {}
