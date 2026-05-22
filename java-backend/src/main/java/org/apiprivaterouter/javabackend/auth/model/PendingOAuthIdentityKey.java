package org.apiprivaterouter.javabackend.auth.model;

public record PendingOAuthIdentityKey(
        String providerType,
        String providerKey,
        String providerSubject
) {
}
