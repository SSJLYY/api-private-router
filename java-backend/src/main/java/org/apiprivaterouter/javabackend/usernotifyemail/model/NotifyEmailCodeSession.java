package org.apiprivaterouter.javabackend.usernotifyemail.model;

import java.time.Instant;

public record NotifyEmailCodeSession(
        String code,
        int attempts,
        Instant createdAt,
        Instant expiresAt
) {
}
