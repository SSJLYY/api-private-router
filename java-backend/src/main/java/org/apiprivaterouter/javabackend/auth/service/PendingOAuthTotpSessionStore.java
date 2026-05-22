package org.apiprivaterouter.javabackend.auth.service;

import org.springframework.stereotype.Component;
import org.apiprivaterouter.javabackend.auth.model.OAuthAdoptionDecisionRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingOAuthTotpSessionStore {

    private final Map<String, PendingSessionBinding> sessions = new ConcurrentHashMap<>();

    public void save(String tempToken, PendingSessionBinding binding) {
        sessions.put(normalize(tempToken), binding);
    }

    public PendingSessionBinding get(String tempToken) {
        return sessions.get(normalize(tempToken));
    }

    public void delete(String tempToken) {
        sessions.remove(normalize(tempToken));
    }

    private String normalize(String tempToken) {
        return tempToken == null ? "" : tempToken.trim();
    }

    public record PendingSessionBinding(
            long pendingSessionId,
            String browserSessionKey,
            OAuthAdoptionDecisionRequest adoptionDecision,
            long userId
    ) {
    }
}
