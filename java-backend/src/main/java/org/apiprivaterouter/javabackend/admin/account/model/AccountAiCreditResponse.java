package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountAiCreditResponse(
        String credit_type,
        Double amount,
        Double minimum_balance
) {
}
