package org.apiprivaterouter.javabackend.auth.model;

public record AuthSendVerifyCodeResponse(
        String message,
        int countdown
) {
}
