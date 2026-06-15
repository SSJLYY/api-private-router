package org.apiprivaterouter.javabackend.userfund.model;

public record LendingFundRequest(
        Long offer_id,
        String remark
) {}
