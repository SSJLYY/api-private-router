package org.apiprivaterouter.javabackend.admin.subscription.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSubscriptionRepositoryTest {

    @Test
    void listSubscriptionsOmitsOptionalPredicatesWhenFiltersAreNull() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminSubscriptionRepository repository = new AdminSubscriptionRepository(jdbcTemplate);

        repository.listSubscriptions(1, 20, null, null, "active", null, "created_at", "desc");

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("and us.status = 'active'")
                        && !sql.contains(":userId is null")
                        && !sql.contains(":groupId is null")
                        && !sql.contains("g.platform = :platform")),
                argThat((SqlParameterSource params) -> !params.hasValue("userId")
                        && !params.hasValue("groupId")
                        && !params.hasValue("platform")),
                eq(Long.class)
        );
    }

    @Test
    void listSubscriptionsIncludesOptionalPredicatesWhenFiltersArePresent() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminSubscriptionRepository repository = new AdminSubscriptionRepository(jdbcTemplate);

        repository.listSubscriptions(1, 20, 2L, 3L, "suspended", "openai", "created_at", "desc");

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("and us.user_id = :userId")
                        && sql.contains("and us.group_id = :groupId")
                        && sql.contains("and g.platform = :platform")
                        && sql.contains("and us.status = :status")),
                argThat((SqlParameterSource params) -> {
                    assertEquals(2L, params.getValue("userId"));
                    assertEquals(3L, params.getValue("groupId"));
                    assertEquals("openai", params.getValue("platform"));
                    assertEquals("suspended", params.getValue("status"));
                    return true;
                }),
                eq(Long.class)
        );
    }
}
