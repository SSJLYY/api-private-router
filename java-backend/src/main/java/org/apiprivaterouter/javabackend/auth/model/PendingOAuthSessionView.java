package org.apiprivaterouter.javabackend.auth.model;

import java.time.Instant;
import java.util.Map;

public record PendingOAuthSessionView(
        long id,
        String sessionToken,
        String intent,
        String providerType,
        String providerKey,
        String providerSubject,
        Long targetUserId,
        String redirectTo,
        String resolvedEmail,
        String registrationPasswordHash,
        Map<String, Object> upstreamIdentityClaims,
        Map<String, Object> localFlowState,
        String browserSessionKey,
        Instant completionCodeExpiresAt,
        Instant emailVerifiedAt,
        Instant passwordVerifiedAt,
        Instant totpVerifiedAt,
        Instant expiresAt,
        Instant consumedAt
) {
}
