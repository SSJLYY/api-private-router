package org.apiprivaterouter.javabackend.userbindings.model;

import java.time.Instant;

public record EmailBindingCodeSession(
        String code,
        int attempts,
        Instant createdAt,
        Instant expiresAt
) {
}
