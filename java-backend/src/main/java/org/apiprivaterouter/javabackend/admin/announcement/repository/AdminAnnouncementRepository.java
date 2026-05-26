package org.apiprivaterouter.javabackend.admin.announcement.repository;

import org.apiprivaterouter.javabackend.admin.announcement.model.AnnouncementReadStatusResponse;
import org.apiprivaterouter.javabackend.admin.announcement.model.AnnouncementResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminAnnouncementRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminAnnouncementRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<AnnouncementResponse> listAnnouncements(int page, int pageSize, String status, String search, String sortBy, String sortOrder) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String resolvedSortBy = switch (sortBy == null ? "" : sortBy.trim()) {
            case "title" -> "title";
            case "status" -> "status";
            case "updated_at" -> "updated_at";
            case "starts_at" -> "starts_at";
            case "ends_at" -> "ends_at";
            default -> "created_at";
        };
        String resolvedSortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        StringBuilder where = new StringBuilder("where 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            where.append(" and status = :status");
            params.addValue("status", normalizedStatus);
        }
        String normalizedSearch = blankToNull(search);
        if (normalizedSearch != null) {
            where.append(" and (title ilike :likeSearch or content ilike :likeSearch)");
            params.addValue("likeSearch", "%" + normalizedSearch + "%");
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from announcements " + where, params, Long.class);
        List<AnnouncementResponse> items = jdbcTemplate.query("""
                select id, title, content, status, notify_mode, targeting, starts_at, ends_at,
                       created_by, updated_by, created_at, updated_at
                from announcements
                """ + where + " order by " + resolvedSortBy + " " + resolvedSortOrder + ", id desc limit :pageSize offset :offset",
                params, (rs, rowNum) -> mapAnnouncement(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<AnnouncementResponse> getAnnouncement(long id) {
        List<AnnouncementResponse> rows = jdbcTemplate.query("""
                select id, title, content, status, notify_mode, targeting, starts_at, ends_at,
                       created_by, updated_by, created_at, updated_at
                from announcements
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapAnnouncement(rs));
        return rows.stream().findFirst();
    }

    public AnnouncementResponse createAnnouncement(String title, String content, String status, String notifyMode,
                                                   String targetingJson, Instant startsAt, Instant endsAt, Long actorId) {
        Long id = jdbcTemplate.query("""
                insert into announcements (
                    title, content, status, notify_mode, targeting, starts_at, ends_at, created_by, updated_by, created_at, updated_at
                ) values (
                    :title, :content, :status, :notifyMode, cast(:targeting as jsonb), :startsAt, :endsAt, :actorId, :actorId, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("title", title)
                .addValue("content", content)
                .addValue("status", status)
                .addValue("notifyMode", notifyMode)
                .addValue("targeting", targetingJson)
                .addValue("startsAt", startsAt == null ? null : Timestamp.from(startsAt))
                .addValue("endsAt", endsAt == null ? null : Timestamp.from(endsAt))
                .addValue("actorId", actorId), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create announcement");
        }
        return getAnnouncement(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
    }

    public AnnouncementResponse updateAnnouncement(long id, String title, String content, String status, String notifyMode,
                                                   String targetingJson, boolean targetingTouched,
                                                   Instant startsAt, boolean startsAtTouched,
                                                   Instant endsAt, boolean endsAtTouched,
                                                   Long actorId) {
        String sql = """
                update announcements
                set title = coalesce(:title, title),
                    content = coalesce(:content, content),
                    status = coalesce(:status, status),
                    notify_mode = coalesce(:notifyMode, notify_mode),
                    targeting = case when :targetingTouched then cast(:targeting as jsonb) else targeting end,
                    starts_at = case when :startsAtTouched then :startsAt else starts_at end,
                    ends_at = case when :endsAtTouched then :endsAt else ends_at end,
                    updated_by = :actorId,
                    updated_at = now()
                where id = :id
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("title", title)
                .addValue("content", content)
                .addValue("status", status)
                .addValue("notifyMode", notifyMode)
                .addValue("targetingTouched", targetingTouched)
                .addValue("targeting", targetingJson)
                .addValue("startsAtTouched", startsAtTouched)
                .addValue("startsAt", startsAt == null ? null : Timestamp.from(startsAt))
                .addValue("endsAtTouched", endsAtTouched)
                .addValue("endsAt", endsAt == null ? null : Timestamp.from(endsAt))
                .addValue("actorId", actorId));
        return getAnnouncement(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
    }

    public void deleteAnnouncement(long id) {
        jdbcTemplate.update("delete from announcements where id = :id", new MapSqlParameterSource("id", id));
    }

    public PageResponse<AnnouncementReadStatusResponse> listReadStatus(long id, int page, int pageSize, String search, String sortBy, String sortOrder) {
        Map<String, Object> targeting = getAnnouncement(id)
                .map(AnnouncementResponse::targeting)
                .orElseThrow(() -> new IllegalArgumentException("announcement not found"));
        int offset = Math.max(page - 1, 0) * pageSize;
        String resolvedSortBy = switch (sortBy == null ? "" : sortBy.trim()) {
            case "username" -> "u.username";
            case "balance" -> "u.balance";
            case "read_at" -> "ar.read_at";
            default -> "u.email";
        };
        String resolvedSortOrder = "desc".equalsIgnoreCase(sortOrder) ? "desc" : "asc";
        String where = """
                where (:search is null or :search = '' or u.email ilike :likeSearch or u.username ilike :likeSearch)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("announcementId", id)
                .addValue("search", search)
                .addValue("likeSearch", search == null || search.isBlank() ? null : "%" + search.trim() + "%")
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from users u
                left join announcement_reads ar
                  on ar.user_id = u.id and ar.announcement_id = :announcementId
                """ + where, params, Long.class);
        List<AnnouncementReadStatusResponse> items = jdbcTemplate.query("""
                select u.id as user_id,
                       coalesce(u.email, '') as email,
                       coalesce(u.username, '') as username,
                       coalesce(u.balance, 0) as balance,
                       (ar.read_at is not null) as has_read,
                       ar.read_at
                from users u
                left join announcement_reads ar
                  on ar.user_id = u.id and ar.announcement_id = :announcementId
                """ + where + " order by " + resolvedSortBy + " " + resolvedSortOrder + ", u.id asc limit :pageSize offset :offset",
                params, (rs, rowNum) -> new AnnouncementReadStatusResponse(
                        rs.getLong("user_id"),
                        rs.getString("email"),
                        rs.getString("username"),
                        rs.getDouble("balance"),
                        computeEligible(targeting, rs.getLong("user_id"), rs.getDouble("balance")),
                        toIsoString(rs.getTimestamp("read_at"))
                ));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    private AnnouncementResponse mapAnnouncement(ResultSet rs) throws SQLException {
        return new AnnouncementResponse(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getString("notify_mode"),
                toTargetingMap(rs.getString("targeting")),
                toIsoString(rs.getTimestamp("starts_at")),
                toIsoString(rs.getTimestamp("ends_at")),
                rs.getObject("created_by", Long.class),
                rs.getObject("updated_by", Long.class),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private Map<String, Object> toTargetingMap(String raw) {
        Map<String, Object> parsed = new LinkedHashMap<>(jsonHelper.readObjectMap(raw));
        if (parsed.isEmpty()) {
            parsed.put("any_of", List.of());
        }
        return parsed;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean computeEligible(Map<String, Object> targeting, long userId, double balance) {
        List<Map<String, Object>> groups = jsonHelper.readObjectList(jsonHelper.writeJson(targeting.get("any_of")));
        if (groups.isEmpty()) {
            return true;
        }
        List<Long> activeGroupIds = loadActiveSubscriptionGroupIds(userId);
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> conditions = jsonHelper.readObjectList(jsonHelper.writeJson(group.get("all_of")));
            if (conditions.isEmpty()) {
                continue;
            }
            boolean allMatched = true;
            for (Map<String, Object> condition : conditions) {
                if (!matchesCondition(condition, balance, activeGroupIds)) {
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

    private boolean matchesCondition(Map<String, Object> condition, double balance, List<Long> activeGroupIds) {
        String type = stringValue(condition.get("type"));
        String operator = stringValue(condition.get("operator"));
        if ("subscription".equals(type)) {
            if (!"in".equals(operator)) {
                return false;
            }
            List<Long> groupIds = toLongList(condition.get("group_ids"));
            if (groupIds.isEmpty() || activeGroupIds.isEmpty()) {
                return false;
            }
            return groupIds.stream().anyMatch(activeGroupIds::contains);
        }
        if ("balance".equals(type)) {
            double value = asDouble(condition.get("value"));
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase();
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .map(item -> {
                    if (item instanceof Number number) {
                        return number.longValue();
                    }
                    try {
                        return Long.parseLong(String.valueOf(item));
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .filter(item -> item != null && item > 0)
                .toList();
    }
}
