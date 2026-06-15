package org.apiprivaterouter.javabackend.admin.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserRepositoryTest {

    @Test
    void listUsersOmitsOptionalPredicatesWhenFiltersAreBlank() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminUserRepository repository = new AdminUserRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.listUsers(1, 20, null, "", " ", null, null, null);

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("where deleted_at is null")
                        && !sql.contains(":status")
                        && !sql.contains(":role")
                        && !sql.contains(":likeSearch")),
                argThat((SqlParameterSource params) -> !params.hasValue("status")
                        && !params.hasValue("role")
                        && !params.hasValue("likeSearch")),
                eq(Long.class)
        );
    }

    @Test
    void listUsersIncludesOptionalPredicatesWhenFiltersArePresent() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminUserRepository repository = new AdminUserRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.listUsers(2, 10, "active", "admin", "alice", null, null, null);

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("and status = :status")
                        && sql.contains("and role = :role")
                        && sql.contains("email ilike :likeSearch")),
                argThat((SqlParameterSource params) -> {
                    assertEquals("active", params.getValue("status"));
                    assertEquals("admin", params.getValue("role"));
                    assertEquals("%alice%", params.getValue("likeSearch"));
                    return true;
                }),
                eq(Long.class)
        );
    }
}
