package org.apiprivaterouter.javabackend.usertotp.model;

public record TotpDisableRequest(
        String email_code,
        String password
) {
}
