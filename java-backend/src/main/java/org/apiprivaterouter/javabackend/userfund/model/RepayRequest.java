package org.apiprivaterouter.javabackend.userfund.model;

public record RepayRequest(
        long loan_id,
        Double amount
) {}
