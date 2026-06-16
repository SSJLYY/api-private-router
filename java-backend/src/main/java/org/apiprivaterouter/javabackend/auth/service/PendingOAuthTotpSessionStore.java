package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.auth.model.OAuthAdoptionDecisionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class PendingOAuthTotpSessionStore {

    private static final Logger log = LoggerFactory.getLogger(PendingOAuthTotpSessionStore.class);
    private static final long TTL_SECONDS = 15 * 60;
    private static final String KEY_PREFIX = "pending:oauth:totp:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PendingOAuthTotpSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void save(String tempToken, PendingSessionBinding binding) {
        try {
            String json = objectMapper.writeValueAsString(new StoredBinding(
                    binding.pendingSessionId(),
                    binding.browserSessionKey(),
                    binding.adoptionDecision(),
                    binding.userId(),
                    binding.expiresAt().toString()
            ));
            long ttl = Math.max(1, binding.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            redis.opsForValue().set(KEY_PREFIX + normalize(tempToken), json, ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.error("failed to serialize pending OAuth TOTP session: {}", e.getMessage());
        }
    }

    public PendingSessionBinding get(String tempToken) {
        String json = redis.opsForValue().get(KEY_PREFIX + normalize(tempToken));
        if (json == null) {
            return null;
        }
        try {
            StoredBinding stored = objectMapper.readValue(json, StoredBinding.class);
            Instant expiresAt = Instant.parse(stored.expiresAt);
            if (expiresAt.isBefore(Instant.now())) {
                delete(tempToken);
                return null;
            }
            return new PendingSessionBinding(
                    stored.pendingSessionId,
                    stored.browserSessionKey,
                    stored.adoptionDecision,
                    stored.userId,
                    expiresAt
            );
        } catch (JsonProcessingException e) {
            log.error("failed to deserialize pending OAuth TOTP session: {}", e.getMessage());
            delete(tempToken);
            return null;
        }
    }

    public void delete(String tempToken) {
        redis.delete(KEY_PREFIX + normalize(tempToken));
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

    private record StoredBinding(
            long pendingSessionId,
            String browserSessionKey,
            OAuthAdoptionDecisionRequest adoptionDecision,
            long userId,
            String expiresAt
    ) {
    }
}
