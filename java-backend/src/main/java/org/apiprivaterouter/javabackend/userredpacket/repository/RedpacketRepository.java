package org.apiprivaterouter.javabackend.userredpacket.repository;

import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketDetailResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
// TODO: redpackets.code字段需要添加UNIQUE约束，防止生成重复code
public class RedpacketRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RedpacketRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RedpacketRecord> findById(long id) {
        List<RedpacketRecord> rows = jdbcTemplate.query("""
                select id, creator_id, code, redpacket_type, total_amount, remaining_amount,
                       total_count, remaining_count, memo, expire_at, created_at
                from redpackets
                where id = :id and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRedpacketRecord(rs));
        return rows.stream().findFirst();
    }

    public Optional<RedpacketRecord> findByCode(String code) {
        List<RedpacketRecord> rows = jdbcTemplate.query("""
                select id, creator_id, code, redpacket_type, total_amount, remaining_amount,
                       total_count, remaining_count, memo, expire_at, created_at
                from redpackets
                where code = :code and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("code", code), (rs, rowNum) -> mapRedpacketRecord(rs));
        return rows.stream().findFirst();
    }

    public Optional<RedpacketRecord> findByCodeForUpdate(String code) {
        List<RedpacketRecord> rows = jdbcTemplate.query("""
                select id, creator_id, code, redpacket_type, total_amount, remaining_amount,
                       total_count, remaining_count, memo, expire_at, created_at
                from redpackets
                where code = :code and deleted_at is null
                for update
                """, new MapSqlParameterSource("code", code), (rs, rowNum) -> mapRedpacketRecord(rs));
        return rows.stream().findFirst();
    }

    public RedpacketRecord create(
            long creatorId,
            String code,
            String redpacketType,
            BigDecimal totalAmount,
            BigDecimal remainingAmount,
            int totalCount,
            int remainingCount,
            String memo,
            OffsetDateTime expireAt
    ) {
        RedpacketRecord record = jdbcTemplate.query("""
                insert into redpackets (
                    creator_id, code, redpacket_type, total_amount, remaining_amount,
                    total_count, remaining_count, memo, expire_at, created_at, updated_at
                ) values (
                    :creatorId, :code, :redpacketType, :totalAmount, :remainingAmount,
                    :totalCount, :remainingCount, :memo, :expireAt, now(), now()
                )
                returning id, creator_id, code, redpacket_type, total_amount, remaining_amount,
                          total_count, remaining_count, memo, expire_at, created_at
                """, new MapSqlParameterSource()
                .addValue("creatorId", creatorId)
                .addValue("code", code)
                .addValue("redpacketType", redpacketType)
                .addValue("totalAmount", totalAmount)
                .addValue("remainingAmount", remainingAmount)
                .addValue("totalCount", totalCount)
                .addValue("remainingCount", remainingCount)
                .addValue("memo", memo)
                .addValue("expireAt", expireAt), rs -> rs.next() ? mapRedpacketRecord(rs) : null);
        if (record == null) {
            throw new IllegalStateException("failed to create redpacket");
        }
        return record;
    }

    public void updateRemaining(long id, BigDecimal remainingAmount, int remainingCount) {
        int updated = jdbcTemplate.update("""
                update redpackets
                set remaining_amount = :remainingAmount,
                    remaining_count = :remainingCount,
                    updated_at = now()
                where id = :id and deleted_at is null and remaining_count > 0
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("remainingAmount", remainingAmount)
                .addValue("remainingCount", remainingCount));
        if (updated != 1) {
            throw new IllegalStateException("redpacket already claimed or exhausted");
        }
    }

    public Optional<UserBalanceSnapshot> findUserByIdForUpdate(long userId) {
        List<UserBalanceSnapshot> rows = jdbcTemplate.query("""
                select id, balance
                from users
                where id = :userId and deleted_at is null
                for update
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new UserBalanceSnapshot(
                rs.getLong("id"),
                rs.getBigDecimal("balance")
        ));
        return rows.stream().findFirst();
    }

    public Optional<UserBalanceSnapshot> findUserById(long userId) {
        List<UserBalanceSnapshot> rows = jdbcTemplate.query("""
                select id, balance
                from users
                where id = :userId and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new UserBalanceSnapshot(
                rs.getLong("id"),
                rs.getBigDecimal("balance")
        ));
        return rows.stream().findFirst();
    }

    public void updateUserBalance(long userId, BigDecimal newBalance) {
        int updated = jdbcTemplate.update("""
                update users
                set balance = :balance,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("balance", newBalance));
        if (updated != 1) {
            throw new IllegalStateException("failed to update user balance");
        }
    }

    public Optional<ClaimRecord> findClaim(long redpacketId, long userId) {
        List<ClaimRecord> rows = jdbcTemplate.query("""
                select id, redpacket_id, user_id, amount, balance_before, balance_after, created_at
                from redpacket_claims
                where redpacket_id = :redpacketId and user_id = :userId and deleted_at is null
                limit 1
                """, new MapSqlParameterSource()
                .addValue("redpacketId", redpacketId)
                .addValue("userId", userId), (rs, rowNum) -> mapClaimRecord(rs));
        return rows.stream().findFirst();
    }

    public ClaimRecord createClaim(
            long redpacketId,
            long userId,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter
    ) {
        ClaimRecord record = jdbcTemplate.query("""
                insert into redpacket_claims (
                    redpacket_id, user_id, amount, balance_before, balance_after, created_at
                ) values (
                    :redpacketId, :userId, :amount, :balanceBefore, :balanceAfter, now()
                )
                returning id, redpacket_id, user_id, amount, balance_before, balance_after, created_at
                """, new MapSqlParameterSource()
                .addValue("redpacketId", redpacketId)
                .addValue("userId", userId)
                .addValue("amount", amount)
                .addValue("balanceBefore", balanceBefore)
                .addValue("balanceAfter", balanceAfter), rs -> rs.next() ? mapClaimRecord(rs) : null);
        if (record == null) {
            throw new IllegalStateException("failed to create claim");
        }
        return record;
    }

    public List<RedpacketDetailResponse.ClaimItem> listClaims(long redpacketId) {
        return jdbcTemplate.query("""
                select c.id, c.user_id, u.email as user_email, c.amount, c.created_at
                from redpacket_claims c
                join users u on u.id = c.user_id
                where c.redpacket_id = :redpacketId and c.deleted_at is null
                order by c.created_at asc
                """, new MapSqlParameterSource("redpacketId", redpacketId), (rs, rowNum) -> new RedpacketDetailResponse.ClaimItem(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("user_email"),
                rs.getBigDecimal("amount"),
                false,
                toIsoString(rs.getTimestamp("created_at"))
        ));
    }

    public List<RedpacketResponse> listByCreator(long userId, int limit, int offset) {
        return jdbcTemplate.query("""
                select id, creator_id, code, redpacket_type, total_amount, remaining_amount,
                       total_count, remaining_count, memo, expire_at, created_at
                from redpackets
                where creator_id = :userId and deleted_at is null
                order by created_at desc
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", limit)
                .addValue("offset", offset), (rs, rowNum) -> toRedpacketResponse(rs));
    }

    public long countByCreator(long userId) {
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from redpackets
                where creator_id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), Long.class);
        return total == null ? 0 : total;
    }

    public List<RedpacketResponse> listAll(int limit, int offset) {
        return jdbcTemplate.query("""
                select id, creator_id, code, redpacket_type, total_amount, remaining_amount,
                       total_count, remaining_count, memo, expire_at, created_at
                from redpackets
                where deleted_at is null
                order by created_at desc
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("offset", offset), (rs, rowNum) -> toRedpacketResponse(rs));
    }

    public long countAll() {
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from redpackets
                where deleted_at is null
                """, new MapSqlParameterSource(), Long.class);
        return total == null ? 0 : total;
    }

    private RedpacketRecord mapRedpacketRecord(ResultSet rs) throws SQLException {
        return new RedpacketRecord(
                rs.getLong("id"),
                rs.getLong("creator_id"),
                rs.getString("code"),
                rs.getString("redpacket_type"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("remaining_amount"),
                rs.getInt("total_count"),
                rs.getInt("remaining_count"),
                rs.getString("memo"),
                rs.getObject("expire_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private ClaimRecord mapClaimRecord(ResultSet rs) throws SQLException {
        return new ClaimRecord(
                rs.getLong("id"),
                rs.getLong("redpacket_id"),
                rs.getLong("user_id"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private RedpacketResponse toRedpacketResponse(ResultSet rs) throws SQLException {
        return new RedpacketResponse(
                rs.getLong("id"),
                rs.getLong("creator_id"),
                rs.getString("code"),
                rs.getString("redpacket_type"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("remaining_amount"),
                rs.getInt("total_count"),
                rs.getInt("remaining_count"),
                rs.getString("memo"),
                toIsoString(rs.getTimestamp("expire_at")),
                null,
                toIsoString(rs.getTimestamp("created_at"))
        );
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record RedpacketRecord(
            long id,
            long creatorId,
            String code,
            String redpacketType,
            BigDecimal totalAmount,
            BigDecimal remainingAmount,
            int totalCount,
            int remainingCount,
            String memo,
            OffsetDateTime expireAt,
            OffsetDateTime createdAt
    ) {
    }

    public record UserBalanceSnapshot(
            long id,
            BigDecimal balance
    ) {
    }

    public record ClaimRecord(
            long id,
            long redpacketId,
            long userId,
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            OffsetDateTime createdAt
    ) {
    }
}
