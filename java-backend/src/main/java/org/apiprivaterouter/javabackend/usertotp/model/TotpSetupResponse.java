package org.apiprivaterouter.javabackend.usertotp.model;

public record TotpSetupResponse(
        String secret,
        String qr_code_url,
        String setup_token,
        int countdown
) {
}
