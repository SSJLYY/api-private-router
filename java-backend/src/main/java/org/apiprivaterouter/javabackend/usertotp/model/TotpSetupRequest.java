package org.apiprivaterouter.javabackend.usertotp.model;

public record TotpSetupRequest(
        String email_code,
        String password
) {
}
