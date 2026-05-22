package org.apiprivaterouter.javabackend.admin.tlsfingerprint.repository;

import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.TLSFingerprintProfileResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class AdminTLSFingerprintProfileRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminTLSFingerprintProfileRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<TLSFingerprintProfileResponse> list() {
        return jdbcTemplate.query("""
                select id, name, description, enable_grease,
                       cipher_suites::text as cipher_suites,
                       curves::text as curves,
                       point_formats::text as point_formats,
                       signature_algorithms::text as signature_algorithms,
                       alpn_protocols::text as alpn_protocols,
                       supported_versions::text as supported_versions,
                       key_share_groups::text as key_share_groups,
                       psk_modes::text as psk_modes,
                       extensions::text as extensions,
                       created_at, updated_at
                from tls_fingerprint_profiles
                order by name asc, id asc
                """, (rs, rowNum) -> mapRow(rs));
    }

    public TLSFingerprintProfileResponse getById(long id) {
        List<TLSFingerprintProfileResponse> rows = jdbcTemplate.query("""
                select id, name, description, enable_grease,
                       cipher_suites::text as cipher_suites,
                       curves::text as curves,
                       point_formats::text as point_formats,
                       signature_algorithms::text as signature_algorithms,
                       alpn_protocols::text as alpn_protocols,
                       supported_versions::text as supported_versions,
                       key_share_groups::text as key_share_groups,
                       psk_modes::text as psk_modes,
                       extensions::text as extensions,
                       created_at, updated_at
                from tls_fingerprint_profiles
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRow(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TLSFingerprintProfileResponse create(ProfileMutation mutation) {
        List<TLSFingerprintProfileResponse> rows = jdbcTemplate.query("""
                insert into tls_fingerprint_profiles(
                    name, description, enable_grease,
                    cipher_suites, curves, point_formats, signature_algorithms,
                    alpn_protocols, supported_versions, key_share_groups, psk_modes, extensions,
                    created_at, updated_at
                ) values (
                    :name, :description, :enableGrease,
                    cast(:cipherSuites as jsonb), cast(:curves as jsonb), cast(:pointFormats as jsonb), cast(:signatureAlgorithms as jsonb),
                    cast(:alpnProtocols as jsonb), cast(:supportedVersions as jsonb), cast(:keyShareGroups as jsonb), cast(:pskModes as jsonb), cast(:extensions as jsonb),
                    now(), now()
                )
                returning id, name, description, enable_grease,
                          cipher_suites::text as cipher_suites,
                          curves::text as curves,
                          point_formats::text as point_formats,
                          signature_algorithms::text as signature_algorithms,
                          alpn_protocols::text as alpn_protocols,
                          supported_versions::text as supported_versions,
                          key_share_groups::text as key_share_groups,
                          psk_modes::text as psk_modes,
                          extensions::text as extensions,
                          created_at, updated_at
                """, buildParams(mutation), (rs, rowNum) -> mapRow(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TLSFingerprintProfileResponse update(long id, ProfileMutation mutation) {
        List<TLSFingerprintProfileResponse> rows = jdbcTemplate.query("""
                update tls_fingerprint_profiles
                set name = :name,
                    description = :description,
                    enable_grease = :enableGrease,
                    cipher_suites = cast(:cipherSuites as jsonb),
                    curves = cast(:curves as jsonb),
                    point_formats = cast(:pointFormats as jsonb),
                    signature_algorithms = cast(:signatureAlgorithms as jsonb),
                    alpn_protocols = cast(:alpnProtocols as jsonb),
                    supported_versions = cast(:supportedVersions as jsonb),
                    key_share_groups = cast(:keyShareGroups as jsonb),
                    psk_modes = cast(:pskModes as jsonb),
                    extensions = cast(:extensions as jsonb),
                    updated_at = now()
                where id = :id
                returning id, name, description, enable_grease,
                          cipher_suites::text as cipher_suites,
                          curves::text as curves,
                          point_formats::text as point_formats,
                          signature_algorithms::text as signature_algorithms,
                          alpn_protocols::text as alpn_protocols,
                          supported_versions::text as supported_versions,
                          key_share_groups::text as key_share_groups,
                          psk_modes::text as psk_modes,
                          extensions::text as extensions,
                          created_at, updated_at
                """, buildParams(mutation).addValue("id", id), (rs, rowNum) -> mapRow(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void delete(long id) {
        jdbcTemplate.update("""
                delete from tls_fingerprint_profiles
                where id = :id
                """, new MapSqlParameterSource("id", id));
    }

    private MapSqlParameterSource buildParams(ProfileMutation mutation) {
        return new MapSqlParameterSource()
                .addValue("name", mutation.name())
                .addValue("description", mutation.description())
                .addValue("enableGrease", mutation.enableGrease())
                .addValue("cipherSuites", jsonHelper.writeJson(mutation.cipherSuites()))
                .addValue("curves", jsonHelper.writeJson(mutation.curves()))
                .addValue("pointFormats", jsonHelper.writeJson(mutation.pointFormats()))
                .addValue("signatureAlgorithms", jsonHelper.writeJson(mutation.signatureAlgorithms()))
                .addValue("alpnProtocols", jsonHelper.writeJson(mutation.alpnProtocols()))
                .addValue("supportedVersions", jsonHelper.writeJson(mutation.supportedVersions()))
                .addValue("keyShareGroups", jsonHelper.writeJson(mutation.keyShareGroups()))
                .addValue("pskModes", jsonHelper.writeJson(mutation.pskModes()))
                .addValue("extensions", jsonHelper.writeJson(mutation.extensions()));
    }

    private TLSFingerprintProfileResponse mapRow(ResultSet rs) throws SQLException {
        return new TLSFingerprintProfileResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("enable_grease"),
                readIntegerList(rs.getString("cipher_suites")),
                readIntegerList(rs.getString("curves")),
                readIntegerList(rs.getString("point_formats")),
                readIntegerList(rs.getString("signature_algorithms")),
                readStringList(rs.getString("alpn_protocols")),
                readIntegerList(rs.getString("supported_versions")),
                readIntegerList(rs.getString("key_share_groups")),
                readIntegerList(rs.getString("psk_modes")),
                readIntegerList(rs.getString("extensions")),
                toIso(rs.getTimestamp("created_at")),
                toIso(rs.getTimestamp("updated_at"))
        );
    }

    private List<Integer> readIntegerList(String raw) {
        List<Number> numbers = jsonHelper.readList(raw, Number.class);
        return numbers.stream().map(Number::intValue).toList();
    }

    private List<String> readStringList(String raw) {
        List<String> values = jsonHelper.readList(raw, String.class);
        return values.stream().map(value -> value == null ? "" : value).toList();
    }

    private String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record ProfileMutation(
            String name,
            String description,
            boolean enableGrease,
            List<Integer> cipherSuites,
            List<Integer> curves,
            List<Integer> pointFormats,
            List<Integer> signatureAlgorithms,
            List<String> alpnProtocols,
            List<Integer> supportedVersions,
            List<Integer> keyShareGroups,
            List<Integer> pskModes,
            List<Integer> extensions
    ) {
    }
}
