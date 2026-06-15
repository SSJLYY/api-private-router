package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record RechargeResponse(
        long id,
        long user_id,
        BigDecimal amount,
        BigDecimal fee,
        String channel,
        String external_order_id,
        String status,
        BigDecimal balance_after,
        String completed_at,
        String created_at
) {}
