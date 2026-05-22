package org.apiprivaterouter.javabackend.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthIdentityKey;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSessionView;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthUpsertDecisionInput;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class PendingOAuthRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public PendingOAuthRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<PendingOAuthSessionView> findPendingSessionByToken(String sessionToken) {
        List<PendingOAuthSessionView> rows = jdbcTemplate.query("""
                select id,
                       session_token,
                       intent,
                       provider_type,
                       provider_key,
                       provider_subject,
                       target_user_id,
                       redirect_to,
                       resolved_email,
                       registration_password_hash,
                       upstream_identity_claims::text as upstream_identity_claims_json,
                       local_flow_state::text as local_flow_state_json,
                       browser_session_key,
                       completion_code_expires_at,
                       email_verified_at,
                       password_verified_at,
                       totp_verified_at,
                       expires_at,
                       consumed_at
                from pending_auth_sessions
                where session_token = :sessionToken
                limit 1
                """, new MapSqlParameterSource("sessionToken", trimToEmpty(sessionToken)), this::mapPendingSession);
        return rows.stream().findFirst();
    }

    public PendingOAuthSessionView createPendingSession(CreatePendingSessionInput input) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into pending_auth_sessions (
                    session_token,
                    intent,
                    provider_type,
                    provider_key,
                    provider_subject,
                    target_user_id,
                    redirect_to,
                    resolved_email,
                    registration_password_hash,
                    upstream_identity_claims,
                    local_flow_state,
                    browser_session_key,
                    completion_code_hash,
                    completion_code_expires_at,
                    email_verified_at,
                    password_verified_at,
                    totp_verified_at,
                    expires_at,
                    consumed_at,
                    created_at,
                    updated_at
                ) values (
                    :sessionToken,
                    :intent,
                    :providerType,
                    :providerKey,
                    :providerSubject,
                    :targetUserId,
                    :redirectTo,
                    :resolvedEmail,
                    :registrationPasswordHash,
                    cast(:upstreamIdentityClaimsJson as jsonb),
                    cast(:localFlowStateJson as jsonb),
                    :browserSessionKey,
                    :completionCodeHash,
                    :completionCodeExpiresAt,
                    :emailVerifiedAt,
                    :passwordVerifiedAt,
                    :totpVerifiedAt,
                    :expiresAt,
                    :consumedAt,
                    now(),
                    now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("sessionToken", trimToEmpty(input.sessionToken()))
                .addValue("intent", trimToEmpty(input.intent()))
                .addValue("providerType", trimToEmpty(input.providerType()))
                .addValue("providerKey", trimToEmpty(input.providerKey()))
                .addValue("providerSubject", trimToEmpty(input.providerSubject()))
                .addValue("targetUserId", input.targetUserId())
                .addValue("redirectTo", trimToEmpty(input.redirectTo()))
                .addValue("resolvedEmail", trimToEmpty(input.resolvedEmail()))
                .addValue("registrationPasswordHash", trimToEmpty(input.registrationPasswordHash()))
                .addValue("upstreamIdentityClaimsJson", jsonHelper.writeJson(copyMap(input.upstreamIdentityClaims())))
                .addValue("localFlowStateJson", jsonHelper.writeJson(copyMap(input.localFlowState())))
                .addValue("browserSessionKey", trimToEmpty(input.browserSessionKey()))
                .addValue("completionCodeHash", trimToEmpty(input.completionCodeHash()))
                .addValue("completionCodeExpiresAt", toOffsetDateTime(input.completionCodeExpiresAt()))
                .addValue("emailVerifiedAt", toOffsetDateTime(input.emailVerifiedAt()))
                .addValue("passwordVerifiedAt", toOffsetDateTime(input.passwordVerifiedAt()))
                .addValue("totpVerifiedAt", toOffsetDateTime(input.totpVerifiedAt()))
                .addValue("expiresAt", toOffsetDateTime(input.expiresAt()))
                .addValue("consumedAt", toOffsetDateTime(input.consumedAt())), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create pending auth session");
        }
        return findPendingSessionById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("created pending auth session not found"));
    }

    public PendingOAuthSessionView updatePendingSessionProgress(
            long sessionId,
            String intent,
            String resolvedEmail,
            Long targetUserId,
            Map<String, Object> completionResponse
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("intent", trimToEmpty(intent))
                .addValue("resolvedEmail", trimToEmpty(resolvedEmail))
                .addValue("targetUserId", targetUserId)
                .addValue("localFlowStateJson", jsonHelper.writeJson(Map.of(
                        "completion_response", completionResponse == null ? Map.of() : completionResponse
                )));
        jdbcTemplate.update("""
                update pending_auth_sessions
                set intent = :intent,
                    resolved_email = :resolvedEmail,
                    target_user_id = :targetUserId,
                    local_flow_state = cast(:localFlowStateJson as jsonb),
                    updated_at = now()
                where id = :sessionId
                """, params);
        return findPendingSessionById(sessionId).orElseThrow(() -> new IllegalStateException("pending auth session not found"));
    }

    public Optional<PendingOAuthSessionView> consumePendingSession(long sessionId, String expectedBrowserSessionKey) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("expectedBrowserSessionKey", trimToEmpty(expectedBrowserSessionKey))
                .addValue("now", OffsetDateTime.now());
        List<PendingOAuthSessionView> rows = jdbcTemplate.query("""
                update pending_auth_sessions
                set consumed_at = now(),
                    completion_code_hash = '',
                    completion_code_expires_at = null,
                    local_flow_state = cast(:sanitizedLocalFlowStateJson as jsonb),
                    updated_at = now()
                where id = :sessionId
                  and consumed_at is null
                  and expires_at >= :now
                  and (completion_code_expires_at is null or completion_code_expires_at >= :now)
                  and (:expectedBrowserSessionKey = '' or browser_session_key = :expectedBrowserSessionKey)
                returning id,
                          session_token,
                          intent,
                          provider_type,
                          provider_key,
                          provider_subject,
                          target_user_id,
                          redirect_to,
                          resolved_email,
                          registration_password_hash,
                          upstream_identity_claims::text as upstream_identity_claims_json,
                          local_flow_state::text as local_flow_state_json,
                          browser_session_key,
                          completion_code_expires_at,
                          email_verified_at,
                          password_verified_at,
                          totp_verified_at,
                          expires_at,
                          consumed_at
                """, params.addValue("sanitizedLocalFlowStateJson",
                jsonHelper.writeJson(sanitizeLocalFlowState(
                        findPendingSessionById(sessionId).map(PendingOAuthSessionView::localFlowState).orElse(Map.of())
                ))), this::mapPendingSession);
        return rows.stream().findFirst();
    }

    public long upsertAdoptionDecision(PendingOAuthUpsertDecisionInput input) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into identity_adoption_decisions (
                    pending_auth_session_id,
                    identity_id,
                    adopt_display_name,
                    adopt_avatar,
                    decided_at,
                    created_at,
                    updated_at
                ) values (
                    :pendingAuthSessionId,
                    :identityId,
                    :adoptDisplayName,
                    :adoptAvatar,
                    now(),
                    now(),
                    now()
                )
                on conflict (pending_auth_session_id)
                do update set identity_id = excluded.identity_id,
                              adopt_display_name = excluded.adopt_display_name,
                              adopt_avatar = excluded.adopt_avatar,
                              decided_at = now(),
                              updated_at = now()
                returning id
                """, new MapSqlParameterSource()
                .addValue("pendingAuthSessionId", input.pendingAuthSessionId())
                .addValue("identityId", input.identityId())
                .addValue("adoptDisplayName", input.adoptDisplayName())
                .addValue("adoptAvatar", input.adoptAvatar()), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to upsert identity adoption decision");
        }
        return key.longValue();
    }

    public Optional<AdoptionDecisionRow> findAdoptionDecision(long pendingAuthSessionId) {
        List<AdoptionDecisionRow> rows = jdbcTemplate.query("""
                select id,
                       pending_auth_session_id,
                       identity_id,
                       adopt_display_name,
                       adopt_avatar
                from identity_adoption_decisions
                where pending_auth_session_id = :pendingAuthSessionId
                limit 1
                """, new MapSqlParameterSource("pendingAuthSessionId", pendingAuthSessionId), (rs, rowNum) -> new AdoptionDecisionRow(
                rs.getLong("id"),
                rs.getLong("pending_auth_session_id"),
                rs.getObject("identity_id", Long.class),
                rs.getBoolean("adopt_display_name"),
                rs.getBoolean("adopt_avatar")
        ));
        return rows.stream().findFirst();
    }

    public Optional<AuthIdentityRow> findAuthIdentityOwner(PendingOAuthIdentityKey identity) {
        if (identity == null) {
            return Optional.empty();
        }
        List<AuthIdentityRow> rows = jdbcTemplate.query("""
                select id,
                       user_id,
                       provider_type,
                       provider_key,
                       provider_subject,
                       issuer,
                       metadata::text as metadata_json,
                       verified_at
                from auth_identities
                where provider_type = :providerType
                  and provider_key = :providerKey
                  and provider_subject = :providerSubject
                limit 1
                """, new MapSqlParameterSource()
                .addValue("providerType", trimToEmpty(identity.providerType()))
                .addValue("providerKey", trimToEmpty(identity.providerKey()))
                .addValue("providerSubject", trimToEmpty(identity.providerSubject())), (rs, rowNum) -> new AuthIdentityRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getString("issuer"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                toInstant(rs.getObject("verified_at", OffsetDateTime.class))
        ));
        return rows.stream().findFirst();
    }

    public Optional<AuthIdentityRow> findAuthIdentityForUser(long userId, PendingOAuthIdentityKey identity) {
        if (identity == null) {
            return Optional.empty();
        }
        List<AuthIdentityRow> rows = jdbcTemplate.query("""
                select id,
                       user_id,
                       provider_type,
                       provider_key,
                       provider_subject,
                       issuer,
                       metadata::text as metadata_json,
                       verified_at
                from auth_identities
                where user_id = :userId
                  and provider_type = :providerType
                  and provider_key = :providerKey
                  and provider_subject = :providerSubject
                limit 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("providerType", trimToEmpty(identity.providerType()))
                .addValue("providerKey", trimToEmpty(identity.providerKey()))
                .addValue("providerSubject", trimToEmpty(identity.providerSubject())), (rs, rowNum) -> new AuthIdentityRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getString("issuer"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                toInstant(rs.getObject("verified_at", OffsetDateTime.class))
        ));
        return rows.stream().findFirst();
    }

    public long ensureAuthIdentityForUser(
            long userId,
            PendingOAuthIdentityKey identity,
            String issuer,
            Map<String, Object> metadata
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into auth_identities (
                    user_id,
                    provider_type,
                    provider_key,
                    provider_subject,
                    verified_at,
                    issuer,
                    metadata,
                    created_at,
                    updated_at
                ) values (
                    :userId,
                    :providerType,
                    :providerKey,
                    :providerSubject,
                    now(),
                    :issuer,
                    cast(:metadataJson as jsonb),
                    now(),
                    now()
                )
                on conflict (provider_type, provider_key, provider_subject)
                do update set user_id = excluded.user_id,
                              verified_at = now(),
                              issuer = excluded.issuer,
                              metadata = excluded.metadata,
                              updated_at = now()
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("providerType", trimToEmpty(identity.providerType()))
                .addValue("providerKey", trimToEmpty(identity.providerKey()))
                .addValue("providerSubject", trimToEmpty(identity.providerSubject()))
                .addValue("issuer", trimToNull(issuer))
                .addValue("metadataJson", jsonHelper.writeJson(metadata == null ? Map.of() : metadata)), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create auth identity");
        }
        return key.longValue();
    }

    public void clearDecisionIdentityReferences(long identityId, long exceptDecisionId) {
        jdbcTemplate.update("""
                update identity_adoption_decisions
                set identity_id = null,
                    updated_at = now()
                where identity_id = :identityId
                  and id <> :exceptDecisionId
                """, new MapSqlParameterSource()
                .addValue("identityId", identityId)
                .addValue("exceptDecisionId", exceptDecisionId));
    }

    public void attachDecisionIdentity(long decisionId, long identityId) {
        jdbcTemplate.update("""
                update identity_adoption_decisions
                set identity_id = :identityId,
                    updated_at = now()
                where id = :decisionId
                """, new MapSqlParameterSource()
                .addValue("decisionId", decisionId)
                .addValue("identityId", identityId));
    }

    public void updateUserUsername(long userId, String username) {
        jdbcTemplate.update("""
                update users
                set username = :username,
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("username", trimToEmpty(username)));
    }

    public Optional<PendingOAuthSessionView> findPendingSessionById(long sessionId) {
        List<PendingOAuthSessionView> rows = jdbcTemplate.query("""
                select id,
                       session_token,
                       intent,
                       provider_type,
                       provider_key,
                       provider_subject,
                       target_user_id,
                       redirect_to,
                       resolved_email,
                       registration_password_hash,
                       upstream_identity_claims::text as upstream_identity_claims_json,
                       local_flow_state::text as local_flow_state_json,
                       browser_session_key,
                       completion_code_expires_at,
                       email_verified_at,
                       password_verified_at,
                       totp_verified_at,
                       expires_at,
                       consumed_at
                from pending_auth_sessions
                where id = :sessionId
                limit 1
                """, new MapSqlParameterSource("sessionId", sessionId), this::mapPendingSession);
        return rows.stream().findFirst();
    }

    private PendingOAuthSessionView mapPendingSession(ResultSet rs, int rowNum) throws SQLException {
        return new PendingOAuthSessionView(
                rs.getLong("id"),
                rs.getString("session_token"),
                rs.getString("intent"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getObject("target_user_id", Long.class),
                rs.getString("redirect_to"),
                rs.getString("resolved_email"),
                rs.getString("registration_password_hash"),
                jsonHelper.readObjectMap(rs.getString("upstream_identity_claims_json")),
                jsonHelper.readObjectMap(rs.getString("local_flow_state_json")),
                rs.getString("browser_session_key"),
                toInstant(rs.getObject("completion_code_expires_at", OffsetDateTime.class)),
                toInstant(rs.getObject("email_verified_at", OffsetDateTime.class)),
                toInstant(rs.getObject("password_verified_at", OffsetDateTime.class)),
                toInstant(rs.getObject("totp_verified_at", OffsetDateTime.class)),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
                toInstant(rs.getObject("consumed_at", OffsetDateTime.class))
        );
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

    private Map<String, Object> copyMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(input);
    }

    private Map<String, Object> sanitizeLocalFlowState(Map<String, Object> localFlowState) {
        if (localFlowState == null || localFlowState.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> sanitized = new java.util.LinkedHashMap<>(localFlowState);
        Object completion = sanitized.get("completion_response");
        if (completion instanceof Map<?, ?> rawMap) {
            java.util.LinkedHashMap<String, Object> cleaned = new java.util.LinkedHashMap<>();
            rawMap.forEach((key, value) -> cleaned.put(String.valueOf(key), value));
            cleaned.remove("access_token");
            cleaned.remove("refresh_token");
            cleaned.remove("expires_in");
            cleaned.remove("token_type");
            sanitized.put("completion_response", cleaned);
        }
        return sanitized;
    }

    private Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record AdoptionDecisionRow(
            long id,
            long pendingAuthSessionId,
            Long identityId,
            boolean adoptDisplayName,
            boolean adoptAvatar
    ) {
    }

    public record AuthIdentityRow(
            long id,
            long userId,
            String providerType,
            String providerKey,
            String providerSubject,
            String issuer,
            Map<String, Object> metadata,
            Instant verifiedAt
    ) {
        public PendingOAuthIdentityKey identityKey() {
            return new PendingOAuthIdentityKey(providerType, providerKey, providerSubject);
        }
    }

    public record CreatePendingSessionInput(
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
            String completionCodeHash,
            Instant completionCodeExpiresAt,
            Instant emailVerifiedAt,
            Instant passwordVerifiedAt,
            Instant totpVerifiedAt,
            Instant expiresAt,
            Instant consumedAt
    ) {
    }
}
