package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record TransferRequest(
        Long to_user_id,
        String to_username,
        Double amount,
        Double fee,
        String remark,
        String idempotency_key
) {}
