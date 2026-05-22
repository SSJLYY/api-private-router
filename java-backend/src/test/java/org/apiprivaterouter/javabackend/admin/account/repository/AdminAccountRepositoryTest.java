package org.apiprivaterouter.javabackend.admin.account.repository;

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

class AdminAccountRepositoryTest {

    @Test
    void listAccountsOmitsOptionalPredicatesWhenFiltersAreBlank() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminAccountRepository repository = new AdminAccountRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.listAccounts(1, 20, null, "", null, "", null, " ", null, null, false);

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("where a.deleted_at is null")
                        && !sql.contains(":platform")
                        && !sql.contains(":type")
                        && !sql.contains(":status")
                        && !sql.contains(":groupId")
                        && !sql.contains(":privacyMode")
                        && !sql.contains(":likeSearch")),
                argThat((SqlParameterSource params) -> !params.hasValue("platform")
                        && !params.hasValue("type")
                        && !params.hasValue("status")
                        && !params.hasValue("groupId")
                        && !params.hasValue("privacyMode")
                        && !params.hasValue("likeSearch")),
                eq(Long.class)
        );
    }

    @Test
    void listAccountsIncludesExpectedPredicatesWhenFiltersArePresent() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminAccountRepository repository = new AdminAccountRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.listAccounts(1, 20, "openai", "apikey", "active", "2", "__unset__", "ws", "name", "asc", false);

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("and a.platform = :platform")
                        && sql.contains("and a.type = :type")
                        && sql.contains("and a.status = 'active'")
                        && sql.contains("and exists (")
                        && sql.contains("ag1.group_id = :groupId")
                        && sql.contains("coalesce(trim(a.extra->>'privacy_mode'), '') = ''")
                        && sql.contains("a.name ilike :likeSearch")),
                argThat((SqlParameterSource params) -> {
                    assertEquals("openai", params.getValue("platform"));
                    assertEquals("apikey", params.getValue("type"));
                    assertEquals("active", params.getValue("status"));
                    assertEquals(2L, params.getValue("groupId"));
                    assertEquals("__unset__", params.getValue("privacyMode"));
                    assertEquals("%ws%", params.getValue("likeSearch"));
                    return true;
                }),
                eq(Long.class)
        );
    }
}
