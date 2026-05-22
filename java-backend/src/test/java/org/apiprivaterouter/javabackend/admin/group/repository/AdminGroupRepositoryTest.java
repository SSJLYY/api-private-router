package org.apiprivaterouter.javabackend.admin.group.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminGroupRepositoryTest {

    @Test
    void groupNameExistsOmitsExcludePredicateWhenExcludeIdIsNull() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(Boolean.FALSE);
        AdminGroupRepository repository = new AdminGroupRepository(jdbcTemplate, mock(JsonHelper.class));

        boolean exists = repository.groupNameExists("openai", null);

        assertFalse(exists);
        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("lower(name) = lower(:name)") && !sql.contains(":excludeId")),
                argThat((SqlParameterSource params) -> {
                    assertEquals("openai", params.getValue("name"));
                    return !params.hasValue("excludeId");
                }),
                eq(Boolean.class)
        );
    }

    @Test
    void groupNameExistsIncludesExcludePredicateWhenExcludeIdIsPresent() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(Boolean.FALSE);
        AdminGroupRepository repository = new AdminGroupRepository(jdbcTemplate, mock(JsonHelper.class));

        boolean exists = repository.groupNameExists("openai", 12L);

        assertFalse(exists);
        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("lower(name) = lower(:name)") && sql.contains("id <> :excludeId")),
                argThat((SqlParameterSource params) -> {
                    assertEquals("openai", params.getValue("name"));
                    assertEquals(12L, params.getValue("excludeId"));
                    return true;
                }),
                eq(Boolean.class)
        );
    }

    @Test
    void listAllActiveGroupsOmitsPlatformPredicateWhenPlatformIsNull() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(java.util.List.of());
        AdminGroupRepository repository = new AdminGroupRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.listAllActiveGroups(null);

        verify(jdbcTemplate).query(
                argThat(sql -> sql.contains("g.status = 'active'") && !sql.contains("g.platform = :platform")),
                argThat((SqlParameterSource params) -> !params.hasValue("platform")),
                any(org.springframework.jdbc.core.RowMapper.class)
        );
    }

    @Test
    void listAllActiveGroupsIncludesPlatformPredicateWhenPlatformIsPresent() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(java.util.List.of());
        AdminGroupRepository repository = new AdminGroupRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.listAllActiveGroups("openai");

        verify(jdbcTemplate).query(
                argThat(sql -> sql.contains("g.platform = :platform")),
                argThat((SqlParameterSource params) -> "openai".equals(params.getValue("platform"))),
                any(org.springframework.jdbc.core.RowMapper.class)
        );
    }
}
