package org.apiprivaterouter.javabackend.admin.promo.repository;

import org.apiprivaterouter.javabackend.admin.promo.model.AdminPromoCodeResponse;
import org.apiprivaterouter.javabackend.admin.promo.model.AdminPromoCodeUsageResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
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
public class AdminPromoRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminPromoRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AdminPromoCodeResponse> listCodes(int page, int pageSize, String status, String search, String sortBy, String sortOrder) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String resolvedSortBy = switch (sortBy == null ? "" : sortBy.trim()) {
            case "bonus_amount" -> "pc.bonus_amount";
            case "status" -> "pc.status";
            case "expires_at" -> "pc.expires_at";
            case "created_at" -> "pc.created_at";
            case "code" -> "pc.code";
            default -> "pc.id";
        };
        String resolvedSortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        String where = """
                where (:status is null or :status = '' or pc.status = :status)
                  and (:search is null or :search = '' or pc.code ilike :likeSearch)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", blankToNull(status))
                .addValue("search", blankToNull(search))
                .addValue("likeSearch", search == null || search.isBlank() ? null : "%" + search.trim() + "%")
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from promo_codes pc
                """ + where, params, Long.class);
        List<AdminPromoCodeResponse> items = jdbcTemplate.query("""
                select pc.id, pc.code, pc.bonus_amount, pc.max_uses, pc.used_count, pc.status,
                       pc.expires_at, coalesce(pc.notes, '') as notes, pc.created_at, pc.updated_at
                from promo_codes pc
                """ + where + " order by " + resolvedSortBy + " " + resolvedSortOrder + ", pc.id desc limit :pageSize offset :offset",
                params, (rs, rowNum) -> mapPromoCode(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<AdminPromoCodeResponse> getCode(long id) {
        List<AdminPromoCodeResponse> rows = jdbcTemplate.query("""
                select pc.id, pc.code, pc.bonus_amount, pc.max_uses, pc.used_count, pc.status,
                       pc.expires_at, coalesce(pc.notes, '') as notes, pc.created_at, pc.updated_at
                from promo_codes pc
                where pc.id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapPromoCode(rs));
        return rows.stream().findFirst();
    }

    public long createCode(String code, double bonusAmount, int maxUses, Instant expiresAt, String notes) {
        Long id = jdbcTemplate.query("""
                insert into promo_codes (code, bonus_amount, max_uses, used_count, status, expires_at, notes, created_at, updated_at)
                values (:code, :bonusAmount, :maxUses, 0, 'active', :expiresAt, :notes, now(), now())
                returning id
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("bonusAmount", bonusAmount)
                .addValue("maxUses", maxUses)
                .addValue("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .addValue("notes", blankToNull(notes)), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create promo code");
        }
        return id;
    }

    public void updateCode(long id, String code, double bonusAmount, int maxUses, String status, Instant expiresAt, String notes) {
        jdbcTemplate.update("""
                update promo_codes
                set code = :code,
                    bonus_amount = :bonusAmount,
                    max_uses = :maxUses,
                    status = :status,
                    expires_at = :expiresAt,
                    notes = :notes,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("bonusAmount", bonusAmount)
                .addValue("maxUses", maxUses)
                .addValue("status", status)
                .addValue("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .addValue("notes", blankToNull(notes)));
    }

    public void deleteCode(long id) {
        jdbcTemplate.update("delete from promo_codes where id = :id", new MapSqlParameterSource("id", id));
    }

    public PageResponse<AdminPromoCodeUsageResponse> listUsages(long promoCodeId, int page, int pageSize) {
        int offset = Math.max(page - 1, 0) * pageSize;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("promoCodeId", promoCodeId)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from promo_code_usages pu
                where pu.promo_code_id = :promoCodeId
                """, params, Long.class);
        List<AdminPromoCodeUsageResponse> items = jdbcTemplate.query("""
                select pu.id, pu.promo_code_id, pu.user_id, pu.bonus_amount, pu.used_at,
                       coalesce(u.email, '') as user_email, coalesce(u.username, '') as user_username
                from promo_code_usages pu
                left join users u on u.id = pu.user_id
                where pu.promo_code_id = :promoCodeId
                order by pu.id desc
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> mapUsage(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    private AdminPromoCodeResponse mapPromoCode(ResultSet rs) throws SQLException {
        return new AdminPromoCodeResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getDouble("bonus_amount"),
                rs.getInt("max_uses"),
                rs.getInt("used_count"),
                rs.getString("status"),
                toIsoString(rs.getTimestamp("expires_at")),
                rs.getString("notes"),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private AdminPromoCodeUsageResponse mapUsage(ResultSet rs) throws SQLException {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", rs.getLong("user_id"));
        user.put("email", rs.getString("user_email"));
        user.put("username", rs.getString("user_username"));
        return new AdminPromoCodeUsageResponse(
                rs.getLong("id"),
                rs.getLong("promo_code_id"),
                rs.getLong("user_id"),
                rs.getDouble("bonus_amount"),
                toIsoString(rs.getTimestamp("used_at")),
                user
        );
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
