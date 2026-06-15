package org.apiprivaterouter.javabackend.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.apiprivaterouter.javabackend.auth.model.OAuthAdoptionDecisionRequest;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
// TODO: ConcurrentHashMap不支持集群部署，生产环境应迁移到Redis存储
public class PendingOAuthTotpSessionStore {

    private static final long TTL_SECONDS = 15 * 60; // 15 minutes

    private final Map<String, PendingSessionBinding> sessions = new ConcurrentHashMap<>();

    public void save(String tempToken, PendingSessionBinding binding) {
        sessions.put(normalize(tempToken), binding);
    }

    public PendingSessionBinding get(String tempToken) {
        PendingSessionBinding binding = sessions.get(normalize(tempToken));
        if (binding != null && binding.expiresAt().isBefore(Instant.now())) {
            sessions.remove(normalize(tempToken));
            return null;
        }
        return binding;
    }

    public void delete(String tempToken) {
        sessions.remove(normalize(tempToken));
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void evictExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, PendingSessionBinding>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingSessionBinding> entry = it.next();
            if (entry.getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
    }

    private String normalize(String tempToken) {
        return tempToken == null ? "" : tempToken.trim();
    }

    public record PendingSessionBinding(
            long pendingSessionId,
            String browserSessionKey,
            OAuthAdoptionDecisionRequest adoptionDecision,
            long userId,
            Instant expiresAt
    ) {
        public PendingSessionBinding(long pendingSessionId, String browserSessionKey,
                                     OAuthAdoptionDecisionRequest adoptionDecision, long userId) {
            this(pendingSessionId, browserSessionKey, adoptionDecision, userId,
                    Instant.now().plusSeconds(TTL_SECONDS));
        }
    }
}
