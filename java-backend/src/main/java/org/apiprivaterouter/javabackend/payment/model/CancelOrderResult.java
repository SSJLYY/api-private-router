package org.apiprivaterouter.javabackend.payment.model;

public record CancelOrderResult(
        long order_id,
        String status
) {
}
