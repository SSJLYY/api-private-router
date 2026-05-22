package org.apiprivaterouter.javabackend.admin.system.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminSystemRepository {

    private static final String LOCK_SCOPE = "admin.system.operations.global_lock";
    private static final String LOCK_KEY = "global-system-operation-lock";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED_RETRYABLE = "failed_retryable";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminSystemRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SystemOperationRecord> findLockRecord() {
        List<SystemOperationRecord> rows = jdbcTemplate.query("""
                select id,
                       scope,
                       idempotency_key_hash,
                       request_fingerprint,
                       status,
                       response_status,
                       response_body,
                       error_reason,
                       locked_until,
                       expires_at,
                       created_at,
                       updated_at
                from idempotency_records
                where scope = :scope
                  and idempotency_key_hash = :keyHash
                limit 1
                """, new MapSqlParameterSource()
                .addValue("scope", LOCK_SCOPE)
                .addValue("keyHash", LOCK_KEY), this::mapRecord);
        return rows.stream().findFirst();
    }

    public boolean tryCreateProcessing(String operationId, Instant lockedUntil, Instant expiresAt) {
        try {
            int updated = jdbcTemplate.update("""
                    insert into idempotency_records (
                        scope,
                        idempotency_key_hash,
                        request_fingerprint,
                        status,
                        locked_until,
                        expires_at,
                        created_at,
                        updated_at
                    ) values (
                        :scope,
                        :keyHash,
                        :requestFingerprint,
                        :status,
                        :lockedUntil,
                        :expiresAt,
                        now(),
                        now()
                    )
                    """, new MapSqlParameterSource()
                    .addValue("scope", LOCK_SCOPE)
                    .addValue("keyHash", LOCK_KEY)
                    .addValue("requestFingerprint", operationId)
                    .addValue("status", STATUS_PROCESSING)
                    .addValue("lockedUntil", toOffsetDateTime(lockedUntil))
                    .addValue("expiresAt", toOffsetDateTime(expiresAt)));
            return updated > 0;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public boolean tryReclaim(long recordId, String fromStatus, Instant now, Instant lockedUntil, Instant expiresAt, String operationId) {
        int updated = jdbcTemplate.update("""
                update idempotency_records
                set request_fingerprint = :requestFingerprint,
                    status = :status,
                    response_status = null,
                    response_body = null,
                    error_reason = null,
                    locked_until = :lockedUntil,
                    expires_at = :expiresAt,
                    updated_at = now()
                where id = :id
                  and status = :fromStatus
                  and (locked_until is null or locked_until <= :now)
                """, new MapSqlParameterSource()
                .addValue("id", recordId)
                .addValue("fromStatus", fromStatus)
                .addValue("requestFingerprint", operationId)
                .addValue("status", STATUS_PROCESSING)
                .addValue("lockedUntil", toOffsetDateTime(lockedUntil))
                .addValue("expiresAt", toOffsetDateTime(expiresAt))
                .addValue("now", toOffsetDateTime(now)));
        return updated > 0;
    }

    public void markSucceeded(long recordId, String operationId, String responseBody, Instant expiresAt) {
        jdbcTemplate.update("""
                update idempotency_records
                set request_fingerprint = :requestFingerprint,
                    status = :status,
                    response_status = 200,
                    response_body = :responseBody,
                    error_reason = null,
                    locked_until = null,
                    expires_at = :expiresAt,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", recordId)
                .addValue("requestFingerprint", operationId)
                .addValue("status", STATUS_SUCCEEDED)
                .addValue("responseBody", responseBody)
                .addValue("expiresAt", toOffsetDateTime(expiresAt)));
    }

    public void markFailed(long recordId, String operationId, String errorReason, Instant lockedUntil, Instant expiresAt) {
        jdbcTemplate.update("""
                update idempotency_records
                set request_fingerprint = :requestFingerprint,
                    status = :status,
                    response_status = null,
                    response_body = null,
                    error_reason = :errorReason,
                    locked_until = :lockedUntil,
                    expires_at = :expiresAt,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", recordId)
                .addValue("requestFingerprint", operationId)
                .addValue("status", STATUS_FAILED_RETRYABLE)
                .addValue("errorReason", errorReason)
                .addValue("lockedUntil", toOffsetDateTime(lockedUntil))
                .addValue("expiresAt", toOffsetDateTime(expiresAt)));
    }

    private SystemOperationRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new SystemOperationRecord(
                rs.getLong("id"),
                rs.getString("scope"),
                rs.getString("idempotency_key_hash"),
                rs.getString("request_fingerprint"),
                rs.getString("status"),
                rs.getObject("response_status", Integer.class),
                rs.getString("response_body"),
                rs.getString("error_reason"),
                toInstant(rs.getObject("locked_until", OffsetDateTime.class)),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class))
        );
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record SystemOperationRecord(
            long id,
            String scope,
            String idempotencyKeyHash,
            String requestFingerprint,
            String status,
            Integer responseStatus,
            String responseBody,
            String errorReason,
            Instant lockedUntil,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
