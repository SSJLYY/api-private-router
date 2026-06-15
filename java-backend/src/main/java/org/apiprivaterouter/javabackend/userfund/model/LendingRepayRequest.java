package org.apiprivaterouter.javabackend.userfund.model;

public record LendingRepayRequest(
        Long offer_id,
        Double amount,
        String remark
) {}
