package org.apiprivaterouter.javabackend.userfund.model;

public record AdminCreditUpdateRequest(
        long user_id,
        Double credit_limit,
        Double interest_rate
) {}
