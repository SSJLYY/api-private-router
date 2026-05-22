package org.apiprivaterouter.javabackend.gateway.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class GatewayOpenAiResponseBindingRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    public GatewayOpenAiResponseBindingRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void store(ResponseBindingRow row) {
        ensureSchema();
        jdbcTemplate.update("""
                insert into java_openai_response_bindings (
                    api_key_id,
                    route_key,
                    response_id,
                    account_id,
                    requested_model,
                    session_model,
                    prompt_cache_key,
                    created_at,
                    expires_at
                )
                values (
                    :apiKeyId,
                    :routeKey,
                    :responseId,
                    :accountId,
                    :requestedModel,
                    :sessionModel,
                    :promptCacheKey,
                    :createdAt,
                    :expiresAt
                )
                on conflict (api_key_id, route_key, response_id)
                do update set account_id = excluded.account_id,
                              requested_model = excluded.requested_model,
                              session_model = excluded.session_model,
                              prompt_cache_key = excluded.prompt_cache_key,
                              created_at = excluded.created_at,
                              expires_at = excluded.expires_at
                """, new MapSqlParameterSource()
                .addValue("apiKeyId", row.apiKeyId())
                .addValue("routeKey", row.routeKey())
                .addValue("responseId", row.responseId())
                .addValue("accountId", row.accountId())
                .addValue("requestedModel", blankToNull(row.requestedModel()))
                .addValue("sessionModel", blankToNull(row.sessionModel()))
                .addValue("promptCacheKey", blankToNull(row.promptCacheKey()))
                .addValue("createdAt", toOffsetDateTime(row.createdAt()))
                .addValue("expiresAt", toOffsetDateTime(row.expiresAt())));
    }

    public Optional<ResponseBindingRow> find(long apiKeyId, String routeKey, String responseId) {
        ensureSchema();
        List<ResponseBindingRow> rows = jdbcTemplate.query("""
                select api_key_id,
                       route_key,
                       response_id,
                       account_id,
                       requested_model,
                       session_model,
                       prompt_cache_key,
                       created_at,
                       expires_at
                from java_openai_response_bindings
                where api_key_id = :apiKeyId
                  and route_key = :routeKey
                  and response_id = :responseId
                limit 1
                """, new MapSqlParameterSource()
                .addValue("apiKeyId", apiKeyId)
                .addValue("routeKey", normalizeRouteKey(routeKey))
                .addValue("responseId", normalizeText(responseId)), (rs, rowNum) -> new ResponseBindingRow(
                rs.getLong("api_key_id"),
                normalizeRouteKey(rs.getString("route_key")),
                normalizeText(rs.getString("response_id")),
                rs.getLong("account_id"),
                normalizeText(rs.getString("requested_model")),
                normalizeText(rs.getString("session_model")),
                normalizeText(rs.getString("prompt_cache_key")),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("expires_at", OffsetDateTime.class))
        ));
        return rows.stream().findFirst();
    }

    public int deleteExpired(Instant now) {
        ensureSchema();
        return jdbcTemplate.update("""
                delete from java_openai_response_bindings
                where expires_at <= :expiresAt
                """, new MapSqlParameterSource("expiresAt", toOffsetDateTime(now)));
    }

    private void initSchema() {
        jdbcTemplate.getJdbcTemplate().execute("""
                create table if not exists java_openai_response_bindings (
                    api_key_id bigint not null,
                    route_key varchar(255) not null,
                    response_id varchar(255) not null,
                    account_id bigint not null,
                    requested_model varchar(255),
                    session_model varchar(255),
                    prompt_cache_key varchar(255),
                    created_at timestamptz not null default now(),
                    expires_at timestamptz not null,
                    primary key (api_key_id, route_key, response_id)
                )
                """);
        jdbcTemplate.getJdbcTemplate().execute("""
                create index if not exists idx_java_openai_response_bindings_expires_at
                on java_openai_response_bindings(expires_at)
                """);
        jdbcTemplate.getJdbcTemplate().execute("""
                create index if not exists idx_java_openai_response_bindings_account_id
                on java_openai_response_bindings(account_id)
                """);
    }

    private void ensureSchema() {
        if (schemaInitialized.get()) {
            return;
        }
        initSchema();
        schemaInitialized.set(true);
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant == null ? Instant.EPOCH : instant, ZoneOffset.UTC);
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? Instant.EPOCH : dateTime.toInstant();
    }

    private String normalizeRouteKey(String routeKey) {
        return routeKey == null || routeKey.isBlank() ? "responses" : routeKey.trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty() ? null : normalized;
    }

    public record ResponseBindingRow(
            long apiKeyId,
            String routeKey,
            String responseId,
            long accountId,
            String requestedModel,
            String sessionModel,
            String promptCacheKey,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
