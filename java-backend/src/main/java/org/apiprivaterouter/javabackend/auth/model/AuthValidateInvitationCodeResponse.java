package org.apiprivaterouter.javabackend.auth.model;

public record AuthValidateInvitationCodeResponse(
        boolean valid,
        String error_code
) {
}
