package org.apiprivaterouter.javabackend.auth.model;

public record PendingOAuthUpsertDecisionInput(
        long pendingAuthSessionId,
        Long identityId,
        boolean adoptDisplayName,
        boolean adoptAvatar
) {
}
