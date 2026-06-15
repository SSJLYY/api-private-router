package org.apiprivaterouter.javabackend.userfund.model;

public record LoanRequest(
        Double amount,
        Integer duration_days
) {}
