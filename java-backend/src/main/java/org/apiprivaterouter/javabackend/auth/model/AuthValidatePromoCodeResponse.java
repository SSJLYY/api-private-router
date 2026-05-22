package org.apiprivaterouter.javabackend.auth.model;

public record AuthValidatePromoCodeResponse(
        boolean valid,
        Double bonus_amount,
        String error_code,
        String message
) {
}
