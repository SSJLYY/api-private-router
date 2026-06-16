package org.apiprivaterouter.javabackend.admin.proxy.service;

import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.leaderlock.LeaderLockCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ProxyExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ProxyExpiryService.class);
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final LeaderLockCache leaderLock;

    public ProxyExpiryService(NamedParameterJdbcTemplate jdbcTemplate, LeaderLockCache leaderLock) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaderLock = leaderLock;
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void sweepExpiredProxies() {
        Runnable release = leaderLock.tryAcquireOrSkip("proxy_expiry_sweep", LOCK_TTL);
        try {
            List<Map<String, Object>> expired = jdbcTemplate.queryForList("""
                    select p.id, p.fallback_mode, p.backup_proxy_id
                    from proxies p
                    where p.expires_at is not null
                      and p.expires_at < now()
                      and p.deleted_at is null
                    """, new MapSqlParameterSource());

            for (Map<String, Object> row : expired) {
                long proxyId = ((Number) row.get("id")).longValue();
                String fallbackMode = (String) row.get("fallback_mode");
                Long backupProxyId = row.get("backup_proxy_id") instanceof Number n ? n.longValue() : null;

                Long targetProxyId = resolveFallbackTarget(proxyId, fallbackMode, backupProxyId);
                if (targetProxyId == null && "none".equals(fallbackMode)) {
                    continue;
                }

                int updated = jdbcTemplate.update("""
                        update accounts
                        set proxy_id = :targetProxyId,
                            proxy_fallback_origin_id = :originProxyId,
                            updated_at = now()
                        where proxy_id = :proxyId and deleted_at is null
                        """, new MapSqlParameterSource()
                        .addValue("targetProxyId", targetProxyId)
                        .addValue("originProxyId", proxyId)
                        .addValue("proxyId", proxyId));

                if (updated > 0) {
                    log.info("proxy expiry: migrated {} accounts from expired proxy {} to fallback {}",
                            updated, proxyId, targetProxyId != null ? targetProxyId : "direct");
                }
            }
        } finally {
            release.run();
        }
    }

    public void revertProxyFallback(long accountId) {
        Long originProxyId = jdbcTemplate.queryForObject("""
                select proxy_fallback_origin_id from accounts
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", accountId), Long.class);

        if (originProxyId == null) {
            throw new IllegalArgumentException("account has no proxy fallback origin");
        }

        jdbcTemplate.update("""
                update accounts
                set proxy_id = :originProxyId,
                    proxy_fallback_origin_id = null,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("originProxyId", originProxyId)
                .addValue("id", accountId));
    }

    private Long resolveFallbackTarget(long proxyId, String fallbackMode, Long backupProxyId) {
        return switch (fallbackMode) {
            case "direct" -> null;
            case "proxy" -> {
                if (backupProxyId == null) yield null;
                Set<Long> visited = new HashSet<>();
                visited.add(proxyId);
                Long target = backupProxyId;
                while (target != null && visited.contains(target)) {
                    Map<String, Object> next = jdbcTemplate.queryForMap("""
                            select fallback_mode, backup_proxy_id from proxies
                            where id = :id and deleted_at is null
                            """, new MapSqlParameterSource("id", target));
                    String nextMode = (String) next.get("fallback_mode");
                    Long nextBackup = next.get("backup_proxy_id") instanceof Number n ? n.longValue() : null;
                    if (!"proxy".equals(nextMode) || nextBackup == null) {
                        break;
                    }
                    visited.add(target);
                    target = nextBackup;
                }
                yield target;
            }
            default -> null;
        };
    }
}
