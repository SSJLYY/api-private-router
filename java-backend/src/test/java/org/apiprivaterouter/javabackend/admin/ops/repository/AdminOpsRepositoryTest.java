package org.apiprivaterouter.javabackend.admin.ops.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOpsRepositoryTest {

    @Test
    void listRequestDetailsBuildsExpectedSqlAndParams() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        AdminOpsRepository repository = new AdminOpsRepository(
                jdbcTemplate,
                mock(AdminSettingsRepository.class),
                mock(JsonHelper.class)
        );

        Map<String, Object> filter = new java.util.LinkedHashMap<>();
        filter.put("kind", "error");
        filter.put("platform", "openai");
        filter.put("group_id", 2L);
        filter.put("user_id", 3L);
        filter.put("api_key_id", 4L);
        filter.put("account_id", 5L);
        filter.put("model", "gpt-5");
        filter.put("request_id", "req_1");
        filter.put("q", "boom");
        filter.put("min_duration_ms", 10);
        filter.put("max_duration_ms", 20);
        filter.put("start_time", Instant.parse("2026-05-20T00:00:00Z"));
        filter.put("end_time", Instant.parse("2026-05-20T01:00:00Z"));
        filter.put("sort", "duration_desc");

        repository.listRequestDetails(filter, 1, 20);

        verify(jdbcTemplate).queryForObject(
                argThat(sql -> sql.contains("with combined as")
                        && sql.contains("from usage_logs ul")
                        && sql.contains("from ops_error_logs o")
                        && sql.contains("kind = :kind")),
                argThat((SqlParameterSource params) -> {
                    assertEquals("error", params.getValue("kind"));
                    assertEquals("openai", params.getValue("platform"));
                    assertEquals(2L, params.getValue("groupId"));
                    assertEquals(3L, params.getValue("userId"));
                    assertEquals(4L, params.getValue("apiKeyId"));
                    assertEquals(5L, params.getValue("accountId"));
                    assertEquals("gpt-5", params.getValue("model"));
                    assertEquals("req_1", params.getValue("requestId"));
                    assertEquals("%boom%", params.getValue("likeQuery"));
                    assertEquals(10, params.getValue("minDurationMs"));
                    assertEquals(20, params.getValue("maxDurationMs"));
                    return true;
                }),
                eq(Long.class)
        );
        verify(jdbcTemplate).query(
                argThat(sql -> sql.contains("order by duration_ms desc nulls last, created_at desc")),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        );
    }

    @Test
    void isMonitoringEnabledDefaultsToTrueAndHonorsDisabledValues() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AdminSettingsRepository settingsRepository = mock(AdminSettingsRepository.class);
        when(settingsRepository.getSettingValue("ops_monitoring_enabled")).thenReturn(null, "disabled");
        AdminOpsRepository repository = new AdminOpsRepository(
                jdbcTemplate,
                settingsRepository,
                mock(JsonHelper.class)
        );

        assertEquals(true, repository.isMonitoringEnabled());
        assertEquals(false, repository.isMonitoringEnabled());
    }
}
