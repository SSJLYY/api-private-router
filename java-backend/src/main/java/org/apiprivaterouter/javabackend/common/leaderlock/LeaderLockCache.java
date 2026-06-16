package org.apiprivaterouter.javabackend.common.leaderlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Repository
public class LeaderLockCache {

    private static final Logger log = LoggerFactory.getLogger(LeaderLockCache.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LeaderLockCache(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryAcquire(String key, String owner, Duration ttl) {
        String lockKey = "leader_lock:" + key;
        String now = Instant.now().toString();
        String expiresAt = Instant.now().plus(ttl).toString();

        int updated = jdbcTemplate.update("""
                insert into idempotency_records (key, value, expires_at, created_at)
                values (:key, :value, :expiresAt, now())
                on conflict (key) do update set
                    value = case when idempotency_records.expires_at < now() then :value else idempotency_records.value end,
                    expires_at = case when idempotency_records.expires_at < now() then :expiresAt else idempotency_records.expires_at end
                """, new MapSqlParameterSource()
                .addValue("key", lockKey)
                .addValue("value", owner)
                .addValue("expiresAt", expiresAt));

        String currentValue = jdbcTemplate.queryForObject(
                "select value from idempotency_records where key = :key",
                new MapSqlParameterSource("key", lockKey),
                String.class);

        return owner.equals(currentValue);
    }

    public void release(String key, String owner) {
        String lockKey = "leader_lock:" + key;
        jdbcTemplate.update("""
                delete from idempotency_records
                where key = :key and value = :owner
                """, new MapSqlParameterSource()
                .addValue("key", lockKey)
                .addValue("owner", owner));
    }

    public Runnable tryAcquireOrSkip(String key, Duration ttl) {
        String owner = UUID.randomUUID().toString();
        boolean acquired = tryAcquire(key, owner, ttl);
        if (acquired) {
            return () -> release(key, owner);
        }
        return () -> {};
    }
}
