package org.apiprivaterouter.javabackend.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class CurrentUserIdentityRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CurrentUserIdentityRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IdentityBindingRow> listByUserId(long userId) {
        return jdbcTemplate.query("""
                select provider_type,
                       provider_key,
                       provider_subject,
                       issuer,
                       metadata::text as metadata_json,
                       verified_at
                from auth_identities
                where user_id = :userId
                order by id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new IdentityBindingRow(
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getString("issuer"),
                rs.getString("metadata_json"),
                rs.getObject("verified_at", OffsetDateTime.class)
        ));
    }

    public record IdentityBindingRow(
            String provider_type,
            String provider_key,
            String provider_subject,
            String issuer,
            String metadata_json,
            OffsetDateTime verified_at
    ) {
    }
}
