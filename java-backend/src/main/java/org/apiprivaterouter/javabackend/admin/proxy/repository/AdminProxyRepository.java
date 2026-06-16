package org.apiprivaterouter.javabackend.admin.proxy.repository;

import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyAccountSummaryResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminProxyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminProxyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AdminProxyResponse> listProxies(
            int page,
            int pageSize,
            String protocol,
            String status,
            String search,
            String sortBy,
            String sortOrder
    ) {
        int offset = Math.max(page - 1, 0) * pageSize;
        StringBuilder where = new StringBuilder("""
                where p.deleted_at is null
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        String normalizedProtocol = blankToNull(protocol);
        if (normalizedProtocol != null) {
            where.append(" and p.protocol = :protocol");
            params.addValue("protocol", normalizedProtocol);
        }
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            where.append(" and p.status = :status");
            params.addValue("status", normalizedStatus);
        }
        String normalizedSearch = blankToNull(search);
        if (normalizedSearch != null) {
            where.append(" and (p.name ilike :likeSearch or p.host ilike :likeSearch or coalesce(p.username, '') ilike :likeSearch)");
            params.addValue("likeSearch", "%" + normalizedSearch + "%");
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from proxies p
                """ + where, params, Long.class);
        List<AdminProxyResponse> items = jdbcTemplate.query("""
                select p.id, p.name, p.protocol, p.host, p.port, p.username, p.password, p.status,
                       p.created_at, p.updated_at,
                       coalesce(acc.account_count, 0) as account_count
                from proxies p
                left join (
                    select proxy_id, count(*) as account_count
                    from accounts
                    where deleted_at is null and proxy_id is not null
                    group by proxy_id
                ) acc on acc.proxy_id = p.id
                """ + where + buildOrderBy(sortBy, sortOrder) + """
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> mapProxy(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public List<AdminProxyResponse> listAllActive(String protocol, boolean withCount) {
        String sql = """
                select p.id, p.name, p.protocol, p.host, p.port, p.username, p.password, p.status,
                       p.created_at, p.updated_at,
                       coalesce(acc.account_count, 0) as account_count
                from proxies p
                left join (
                    select proxy_id, count(*) as account_count
                    from accounts
                    where deleted_at is null and proxy_id is not null
                    group by proxy_id
                ) acc on acc.proxy_id = p.id
                where p.deleted_at is null
                  and p.status = 'active'
                """ + (blankToNull(protocol) == null ? "" : "\n  and p.protocol = :protocol") + """
                order by """ + (withCount ? "coalesce(acc.account_count, 0) desc, p.created_at desc, p.id desc" : "p.created_at desc, p.id desc");
        MapSqlParameterSource allParams = new MapSqlParameterSource();
        if (blankToNull(protocol) != null) {
            allParams.addValue("protocol", blankToNull(protocol));
        }
        return jdbcTemplate.query(sql, allParams, (rs, rowNum) -> mapProxy(rs));
    }

    public Optional<AdminProxyResponse> getProxy(long id) {
        List<AdminProxyResponse> rows = jdbcTemplate.query("""
                select p.id, p.name, p.protocol, p.host, p.port, p.username, p.password, p.status,
                       p.created_at, p.updated_at,
                       coalesce(acc.account_count, 0) as account_count
                from proxies p
                left join (
                    select proxy_id, count(*) as account_count
                    from accounts
                    where deleted_at is null and proxy_id is not null
                    group by proxy_id
                ) acc on acc.proxy_id = p.id
                where p.id = :id and p.deleted_at is null
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapProxy(rs));
        return rows.stream().findFirst();
    }

    public List<AdminProxyResponse> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select p.id, p.name, p.protocol, p.host, p.port, p.username, p.password, p.status,
                       p.created_at, p.updated_at,
                       coalesce(acc.account_count, 0) as account_count
                from proxies p
                left join (
                    select proxy_id, count(*) as account_count
                    from accounts
                    where deleted_at is null and proxy_id is not null
                    group by proxy_id
                ) acc on acc.proxy_id = p.id
                where p.id in (:ids) and p.deleted_at is null
                order by p.id desc
                """, new MapSqlParameterSource("ids", ids), (rs, rowNum) -> mapProxy(rs));
    }

    public long createProxy(String name, String protocol, String host, int port, String username, String password) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into proxies (name, protocol, host, port, username, password, status, created_at, updated_at)
                values (:name, :protocol, :host, :port, :username, :password, 'active', now(), now())
                returning id
                """, new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("protocol", protocol)
                .addValue("host", host)
                .addValue("port", port)
                .addValue("username", blankToNull(username))
                .addValue("password", blankToNull(password)), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create proxy");
        }
        return key.longValue();
    }

    public int updateProxy(
            long id,
            String name,
            String protocol,
            String host,
            Integer port,
            String username,
            boolean usernamePresent,
            String password,
            boolean passwordPresent,
            String status
    ) {
        StringBuilder sql = new StringBuilder("""
                update proxies
                set updated_at = now()
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
        if (name != null) {
            sql.append(", name = :name");
            params.addValue("name", name);
        }
        if (protocol != null) {
            sql.append(", protocol = :protocol");
            params.addValue("protocol", protocol);
        }
        if (host != null) {
            sql.append(", host = :host");
            params.addValue("host", host);
        }
        if (port != null) {
            sql.append(", port = :port");
            params.addValue("port", port);
        }
        if (usernamePresent) {
            sql.append(", username = :username");
            params.addValue("username", blankToNull(username));
        }
        if (passwordPresent) {
            sql.append(", password = :password");
            params.addValue("password", blankToNull(password));
        }
        if (status != null) {
            sql.append(", status = :status");
            params.addValue("status", status);
        }
        sql.append(" where id = :id and deleted_at is null");
        return jdbcTemplate.update(sql.toString(), params);
    }

    public int softDeleteProxy(long id) {
        return jdbcTemplate.update("""
                update proxies
                set deleted_at = now(), updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public long countAccountsByProxyId(long proxyId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from accounts
                where proxy_id = :proxyId and deleted_at is null
                """, new MapSqlParameterSource("proxyId", proxyId), Long.class);
        return count == null ? 0 : count;
    }

    public long countActiveAccountsByProxyId(long proxyId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from accounts
                where proxy_id = :proxyId and deleted_at is null and status = 'active'
                """, new MapSqlParameterSource("proxyId", proxyId), Long.class);
        return count == null ? 0 : count;
    }

    public boolean existsByHostPortAuth(String host, int port, String username, String password) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from proxies
                    where deleted_at is null
                      and host = :host
                      and port = :port
                      and coalesce(username, '') = :username
                      and coalesce(password, '') = :password
                )
                """, new MapSqlParameterSource()
                .addValue("host", host)
                .addValue("port", port)
                .addValue("username", defaultString(username))
                .addValue("password", defaultString(password)), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<ProxyAccountSummaryResponse> getProxyAccounts(long proxyId) {
        return jdbcTemplate.query("""
                select id, name, platform, type, notes
                from accounts
                where proxy_id = :proxyId and deleted_at is null
                order by id desc
                """, new MapSqlParameterSource("proxyId", proxyId), (rs, rowNum) -> new ProxyAccountSummaryResponse(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("platform")),
                defaultString(rs.getString("type")),
                rs.getString("notes")
        ));
    }

    private AdminProxyResponse mapProxy(ResultSet rs) throws SQLException {
        return new AdminProxyResponse(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("protocol")),
                defaultString(rs.getString("host")),
                rs.getInt("port"),
                rs.getString("username"),
                rs.getString("password"),
                defaultString(rs.getString("status")),
                rs.getObject("account_count", Long.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                rs.getString("expires_at"),
                rs.getString("fallback_mode"),
                rs.getObject("backup_proxy_id", Long.class),
                rs.getObject("expiry_warn_days", Integer.class),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private String buildOrderBy(String sortBy, String sortOrder) {
        String field = switch (sortBy == null ? "" : sortBy.trim()) {
            case "name" -> "p.name";
            case "protocol" -> "p.protocol";
            case "status" -> "p.status";
            case "created_at" -> "p.created_at";
            case "account_count" -> "coalesce(acc.account_count, 0)";
            default -> "p.id";
        };
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        return "\norder by " + field + " " + direction + ", p.id desc\n";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
