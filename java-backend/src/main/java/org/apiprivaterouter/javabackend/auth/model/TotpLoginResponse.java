package org.apiprivaterouter.javabackend.auth.model;

public record TotpLoginResponse(
        boolean requires_2fa,
        String temp_token,
        String user_email_masked
) {
}
