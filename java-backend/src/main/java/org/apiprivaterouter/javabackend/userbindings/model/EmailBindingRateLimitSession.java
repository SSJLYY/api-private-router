package org.apiprivaterouter.javabackend.userbindings.model;

import java.time.Instant;

public record EmailBindingRateLimitSession(
        int count,
        Instant expiresAt
) {
}
