package org.apiprivaterouter.javabackend.admin.apikey.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class DeletedApiKeyAuditRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public DeletedApiKeyAuditRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public void recordDeletedKey(String key, long apiKeyId, long userId, String keyName) {
        try {
            jdbcTemplate.update("""
                    insert into deleted_api_key_audits (key, api_key_id, user_id, key_name, deleted_at, created_at)
                    values (:key, :apiKeyId, :userId, :keyName, now(), now())
                    """, new MapSqlParameterSource()
                    .addValue("key", key)
                    .addValue("apiKeyId", apiKeyId)
                    .addValue("userId", userId)
                    .addValue("keyName", keyName != null ? keyName : ""));
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(DeletedApiKeyAuditRepository.class)
                    .warn("failed to record deleted key audit: {}", ex.getMessage());
        }
    }

    public Optional<Long> findDeletedKeyOwner(String key) {
        try {
            Long userId = jdbcTemplate.queryForObject("""
                    select user_id from deleted_api_key_audits
                    where key = :key
                    order by deleted_at desc
                    limit 1
                    """, new MapSqlParameterSource("key", key), Long.class);
            return Optional.ofNullable(userId);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
