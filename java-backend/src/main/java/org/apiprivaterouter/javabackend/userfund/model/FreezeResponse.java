package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record FreezeResponse(
        long id,
        long user_id,
        BigDecimal amount,
        String reason,
        String status,
        String ref_type,
        Long ref_id,
        String frozen_at,
        String unfrozen_at,
        String unfreeze_reason,
        Long operator_id
) {}
