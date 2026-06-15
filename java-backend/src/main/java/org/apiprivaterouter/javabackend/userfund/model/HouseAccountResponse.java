package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record HouseAccountResponse(
        long id,
        BigDecimal balance,
        BigDecimal total_income,
        BigDecimal total_expense,
        String status,
        String created_at,
        String updated_at
) {}
