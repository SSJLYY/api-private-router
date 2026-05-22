package org.apiprivaterouter.javabackend.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthPublicEmailRepositoryTest {

    @Test
    void incrementUserTokenVersionUpdatesOnlyTokenVersion() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AuthPublicEmailRepository repository = new AuthPublicEmailRepository(jdbcTemplate, mock(JsonHelper.class));

        repository.incrementUserTokenVersion(42L);

        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("token_version = coalesce(token_version, 0) + 1")
                        && !sql.contains("password_hash = :passwordHash")),
                argThat((SqlParameterSource params) -> {
                    assertEquals(42L, params.getValue("userId"));
                    return params instanceof MapSqlParameterSource;
                })
        );
    }
}
