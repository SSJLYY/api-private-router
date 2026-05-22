package org.apiprivaterouter.javabackend.riskcontrol.runtime.repository;

import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ModerationApiKeyContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class GatewayApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public GatewayApiKeyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ModerationApiKeyContext> findByBearerKeyForModeration(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Optional.empty();
        }
        List<ModerationApiKeyContext> rows = jdbcTemplate.query("""
                select
                    k.id as api_key_id,
                    k.name as api_key_name,
                    coalesce(k.status, '') as api_key_status,
                    u.id as user_id,
                    coalesce(u.email, '') as user_email,
                    coalesce(u.status, '') as user_status,
                    coalesce(u.concurrency, 1) as user_concurrency,
                    coalesce(u.role, '') as user_role,
                    k.group_id as group_id,
                    coalesce(g.name, '') as group_name,
                    coalesce(g.platform, '') as group_platform,
                    coalesce(g.subscription_type, '') as group_subscription_type,
                    coalesce(g.allow_image_generation, false) as group_allow_image_generation,
                    coalesce(g.allow_messages_dispatch, false) as group_allow_messages_dispatch,
                    coalesce(g.claude_code_only, false) as group_claude_code_only
                from api_keys k
                join users u on u.id = k.user_id and u.deleted_at is null
                left join groups g on g.id = k.group_id and g.deleted_at is null
                where k.key = :key
                  and k.deleted_at is null
                limit 1
                """, new MapSqlParameterSource("key", rawApiKey.trim()), (rs, rowNum) -> new ModerationApiKeyContext(
                rs.getLong("api_key_id"),
                rs.getString("api_key_name"),
                rs.getString("api_key_status"),
                rs.getLong("user_id"),
                rs.getString("user_email"),
                rs.getString("user_status"),
                rs.getInt("user_concurrency"),
                rs.getString("user_role"),
                rs.getObject("group_id", Long.class),
                rs.getString("group_name"),
                rs.getString("group_platform"),
                rs.getString("group_subscription_type"),
                rs.getBoolean("group_allow_image_generation"),
                rs.getBoolean("group_allow_messages_dispatch"),
                rs.getBoolean("group_claude_code_only")
        ));
        return rows.stream().findFirst();
    }

    public void touchLastUsed(long apiKeyId) {
        jdbcTemplate.update("""
                update api_keys
                set last_used_at = now(),
                    updated_at = now()
                where id = :id
                  and deleted_at is null
                """, new MapSqlParameterSource("id", apiKeyId));
    }
}
