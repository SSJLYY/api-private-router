package org.apiprivaterouter.javabackend.admin.fund.model;

public record AdminFundAdjustRequest(
        Double amount,
        String reason
) {}
