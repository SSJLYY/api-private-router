package org.apiprivaterouter.javabackend.payment.model;

import jakarta.validation.constraints.NotBlank;

public record VerifyOrderRequest(
        @NotBlank String out_trade_no
) {
}
