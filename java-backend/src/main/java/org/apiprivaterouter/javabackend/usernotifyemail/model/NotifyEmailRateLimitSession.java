package org.apiprivaterouter.javabackend.usernotifyemail.model;

import java.time.Instant;

public record NotifyEmailRateLimitSession(
        int count,
        Instant expiresAt
) {
}
