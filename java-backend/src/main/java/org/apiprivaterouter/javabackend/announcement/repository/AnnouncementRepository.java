package org.apiprivaterouter.javabackend.announcement.repository;

import org.apiprivaterouter.javabackend.announcement.model.UserAnnouncementResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class AnnouncementRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AnnouncementRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<UserAnnouncementResponse> listForUser(long userId, boolean unreadOnly) {
        double balance = loadUserBalance(userId);
        List<Long> activeGroupIds = loadActiveSubscriptionGroupIds(userId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select a.id, a.title, a.content, a.notify_mode, a.targeting, a.starts_at, a.ends_at,
                       a.created_at, a.updated_at, ar.read_at
                from announcements a
                left join announcement_reads ar
                  on ar.announcement_id = a.id and ar.user_id = :userId
                where a.status = 'active'
                  and (:unreadOnly = false or ar.read_at is null)
                  and (a.starts_at is null or a.starts_at <= now())
                  and (a.ends_at is null or a.ends_at > now())
                order by a.id desc
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("unreadOnly", unreadOnly));
        return rows.stream()
                .filter(row -> matchesTargeting(row.get("targeting"), balance, activeGroupIds))
                .map(row -> new UserAnnouncementResponse(
                        longValue(row.get("id")),
                        stringValue(row.get("title")),
                        stringValue(row.get("content")),
                        stringValue(row.get("notify_mode")),
                        toIsoString((Timestamp) row.get("starts_at")),
                        toIsoString((Timestamp) row.get("ends_at")),
                        toIsoString((Timestamp) row.get("read_at")),
                        toIsoString((Timestamp) row.get("created_at")),
                        toIsoString((Timestamp) row.get("updated_at"))
                ))
                .toList();
    }

    public boolean markRead(long userId, long announcementId) {
        int updated = jdbcTemplate.update("""
                insert into announcement_reads (announcement_id, user_id, read_at, created_at)
                values (:announcementId, :userId, now(), now())
                on conflict (announcement_id, user_id) do nothing
                """, new MapSqlParameterSource()
                .addValue("announcementId", announcementId)
                .addValue("userId", userId));
        return updated > 0;
    }

    public boolean canUserAccessAnnouncement(long userId, long announcementId) {
        Double balanceValue = jdbcTemplate.queryForObject("""
                select balance
                from users
                where id = :userId
                """, new MapSqlParameterSource("userId", userId), Double.class);
        if (balanceValue == null) {
            return false;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select targeting
                from announcements
                where id = :id
                  and status = 'active'
                  and (starts_at is null or starts_at <= now())
                  and (ends_at is null or ends_at > now())
                """, new MapSqlParameterSource("id", announcementId));
        if (rows.isEmpty()) {
            return false;
        }
        return matchesTargeting(rows.get(0).get("targeting"), balanceValue, loadActiveSubscriptionGroupIds(userId));
    }

    private double loadUserBalance(long userId) {
        Double balance = jdbcTemplate.queryForObject("""
                select balance
                from users
                where id = :userId
                """, new MapSqlParameterSource("userId", userId), Double.class);
        if (balance == null) {
            throw new IllegalArgumentException("user not found");
        }
        return balance;
    }

    private List<Long> loadActiveSubscriptionGroupIds(long userId) {
        return jdbcTemplate.query("""
                select distinct group_id
                from user_subscriptions
                where user_id = :userId
                  and status = 'active'
                  and (starts_at is null or starts_at <= now())
                  and (expires_at is null or expires_at > now())
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getLong("group_id"));
    }

    private boolean matchesTargeting(Object targetingRaw, double balance, List<Long> activeGroupIds) {
        Map<String, Object> targeting = jsonHelper.readObjectMap(String.valueOf(targetingRaw));
        Object anyOfRaw = targeting.get("any_of");
        if (!(anyOfRaw instanceof List<?> groups) || groups.isEmpty()) {
            return true;
        }
        for (Object groupRaw : groups) {
            if (!(groupRaw instanceof Map<?, ?> rawGroup)) {
                continue;
            }
            Object allOfRaw = rawGroup.get("all_of");
            if (!(allOfRaw instanceof List<?> conditions) || conditions.isEmpty()) {
                continue;
            }
            boolean allMatched = true;
            for (Object conditionRaw : conditions) {
                if (!(conditionRaw instanceof Map<?, ?> rawCondition) || !matchesCondition(rawCondition, balance, activeGroupIds)) {
                    allMatched = false;
                    break;
                }
            }
            if (allMatched) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCondition(Map<?, ?> condition, double balance, List<Long> activeGroupIds) {
        String type = stringValue(condition.get("type"));
        String operator = stringValue(condition.get("operator"));
        if ("subscription".equals(type)) {
            if (!"in".equals(operator)) {
                return false;
            }
            List<Long> groupIds = toLongList(condition.get("group_ids"));
            return !groupIds.isEmpty() && groupIds.stream().anyMatch(activeGroupIds::contains);
        }
        if ("balance".equals(type)) {
            double value = doubleValue(condition.get("value"));
            return switch (operator) {
                case "gt" -> balance > value;
                case "gte" -> balance >= value;
                case "lt" -> balance < value;
                case "lte" -> balance <= value;
                case "eq" -> balance == value;
                default -> false;
            };
        }
        return false;
    }

    private List<Long> toLongList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(this::longObject)
                .filter(value -> value != null && value > 0)
                .toList();
    }

    private Long longObject(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private long longValue(Object value) {
        Long result = longObject(value);
        return result == null ? 0 : result;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
