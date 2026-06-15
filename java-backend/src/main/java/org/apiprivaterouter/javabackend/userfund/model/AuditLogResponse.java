package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record AuditLogResponse(
        long id,
        Long user_id,
        String action,
        String target_type,
        Long target_id,
        BigDecimal amount,
        BigDecimal before_value,
        BigDecimal after_value,
        String description,
        String operator_id,
        String operator_role,
        String request_id,
        String status,
        String created_at
) {}
