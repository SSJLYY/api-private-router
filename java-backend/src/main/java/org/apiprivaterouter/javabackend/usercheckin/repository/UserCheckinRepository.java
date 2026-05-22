package org.apiprivaterouter.javabackend.usercheckin.repository;

import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinCalendarDayResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinHistoryItemResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
public class UserCheckinRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserCheckinRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    public Optional<CheckinRecord> findByUserAndDate(long userId, LocalDate checkinDate) {
        List<CheckinRecord> rows = jdbcTemplate.query("""
                select id, user_id, checkin_date, timezone, stake_amount, reward_amount, multiplier,
                       net_change, balance_before, balance_after, checked_in_at
                from user_checkins
                where user_id = :userId
                  and checkin_date = :checkinDate
                  and deleted_at is null
                limit 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("checkinDate", Date.valueOf(checkinDate)), (rs, rowNum) -> mapCheckinRecord(rs));
        return rows.stream().findFirst();
    }

    public CheckinRecord createCheckin(
            long userId,
            LocalDate checkinDate,
            String timezone,
            BigDecimal stakeAmount,
            BigDecimal rewardAmount,
            BigDecimal multiplier,
            BigDecimal netChange,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            OffsetDateTime checkedInAt
    ) {
        try {
            CheckinRecord record = jdbcTemplate.query("""
                    insert into user_checkins (
                        user_id, checkin_date, timezone, stake_amount, reward_amount, multiplier,
                        net_change, balance_before, balance_after, checked_in_at, created_at, updated_at
                    ) values (
                        :userId, :checkinDate, :timezone, :stakeAmount, :rewardAmount, :multiplier,
                        :netChange, :balanceBefore, :balanceAfter, :checkedInAt, now(), now()
                    )
                    returning id, user_id, checkin_date, timezone, stake_amount, reward_amount, multiplier,
                              net_change, balance_before, balance_after, checked_in_at
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("checkinDate", Date.valueOf(checkinDate))
                    .addValue("timezone", timezone)
                    .addValue("stakeAmount", stakeAmount)
                    .addValue("rewardAmount", rewardAmount)
                    .addValue("multiplier", multiplier)
                    .addValue("netChange", netChange)
                    .addValue("balanceBefore", balanceBefore)
                    .addValue("balanceAfter", balanceAfter)
                    .addValue("checkedInAt", checkedInAt), rs -> rs.next() ? mapCheckinRecord(rs) : null);
            if (record == null) {
                throw new IllegalStateException("failed to create checkin");
            }
            return record;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("already checked in today");
        }
    }

    public void updateUserBalance(long userId, BigDecimal balanceAfter) {
        int updated = jdbcTemplate.update("""
                update users
                set balance = :balanceAfter,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("balanceAfter", balanceAfter));
        if (updated != 1) {
            throw new IllegalStateException("failed to update user balance");
        }
    }

    public List<UserCheckinCalendarDayResponse> listMonthCalendar(long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query("""
                select checkin_date, stake_amount, reward_amount, multiplier, net_change, checked_in_at
                from user_checkins
                where user_id = :userId
                  and checkin_date >= :startDate
                  and checkin_date <= :endDate
                  and deleted_at is null
                order by checkin_date asc
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("startDate", Date.valueOf(startDate))
                .addValue("endDate", Date.valueOf(endDate)), (rs, rowNum) -> new UserCheckinCalendarDayResponse(
                rs.getDate("checkin_date").toLocalDate().toString(),
                true,
                rs.getBigDecimal("stake_amount"),
                rs.getBigDecimal("reward_amount"),
                rs.getBigDecimal("multiplier"),
                rs.getBigDecimal("net_change"),
                toIsoString(rs.getTimestamp("checked_in_at"))
        ));
    }

    public List<UserCheckinHistoryItemResponse> listHistory(long userId, int limit, int offset) {
        return jdbcTemplate.query("""
                select id, checkin_date, timezone, stake_amount, reward_amount, multiplier,
                       net_change, balance_before, balance_after, checked_in_at
                from user_checkins
                where user_id = :userId
                  and deleted_at is null
                order by checked_in_at desc, id desc
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", limit)
                .addValue("offset", offset), (rs, rowNum) -> new UserCheckinHistoryItemResponse(
                rs.getLong("id"),
                rs.getDate("checkin_date").toLocalDate().toString(),
                rs.getString("timezone"),
                rs.getBigDecimal("stake_amount"),
                rs.getBigDecimal("reward_amount"),
                rs.getBigDecimal("multiplier"),
                rs.getBigDecimal("net_change"),
                rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"),
                toIsoString(rs.getTimestamp("checked_in_at"))
        ));
    }

    public long countHistory(long userId) {
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from user_checkins
                where user_id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), Long.class);
        return total == null ? 0 : total;
    }

    public ZoneId normalizeTimezone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? ZoneId.systemDefault().getId() : timezone.trim();
        try {
            return ZoneId.of(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid timezone");
        }
    }

    private CheckinRecord mapCheckinRecord(ResultSet rs) throws SQLException {
        return new CheckinRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getDate("checkin_date").toLocalDate(),
                rs.getString("timezone"),
                rs.getBigDecimal("stake_amount"),
                rs.getBigDecimal("reward_amount"),
                rs.getBigDecimal("multiplier"),
                rs.getBigDecimal("net_change"),
                rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"),
                rs.getObject("checked_in_at", OffsetDateTime.class)
        );
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record UserBalanceSnapshot(
            long id,
            BigDecimal balance
    ) {
    }

    public record CheckinRecord(
            long id,
            long userId,
            LocalDate checkinDate,
            String timezone,
            BigDecimal stakeAmount,
            BigDecimal rewardAmount,
            BigDecimal multiplier,
            BigDecimal netChange,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            OffsetDateTime checkedInAt
    ) {
    }
}
