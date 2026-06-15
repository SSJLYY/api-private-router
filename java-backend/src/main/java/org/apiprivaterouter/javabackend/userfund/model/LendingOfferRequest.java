package org.apiprivaterouter.javabackend.userfund.model;

public record LendingOfferRequest(
        Double amount,
        Double interest_rate,
        Integer duration_days
) {}
