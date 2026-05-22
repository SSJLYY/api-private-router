package org.apiprivaterouter.javabackend.admin.payment.model;

public record AdminRefundOrderRequest(
        double amount,
        String reason,
        Boolean deduct_balance,
        Boolean force
) {
}
