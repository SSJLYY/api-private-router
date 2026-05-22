package org.apiprivaterouter.javabackend.usergroups.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UserGroupRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserGroupRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> findAllowedGroupIds(long userId) {
        return jdbcTemplate.query("""
                select group_id
                from user_allowed_groups
                where user_id = :userId
                order by group_id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getLong("group_id"));
    }

    public Map<Long, Double> findRateMultipliers(long userId) {
        Map<Long, Double> rates = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select group_id, rate_multiplier
                from user_group_rate_multipliers
                where user_id = :userId
                  and rate_multiplier is not null
                order by group_id asc
                """, new MapSqlParameterSource("userId", userId), rs -> {
            rates.put(rs.getLong("group_id"), rs.getDouble("rate_multiplier"));
        });
        return rates;
    }
}
