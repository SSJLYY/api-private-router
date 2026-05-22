package org.apiprivaterouter.javabackend.payment.model;

import jakarta.validation.constraints.NotBlank;

public record RefundRequest(
        @NotBlank String reason
) {
}
