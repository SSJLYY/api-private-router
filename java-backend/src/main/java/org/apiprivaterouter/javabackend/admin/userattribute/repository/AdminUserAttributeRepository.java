package org.apiprivaterouter.javabackend.admin.userattribute.repository;

import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeDefinitionResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeOption;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeValidation;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeValueResponse;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminUserAttributeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminUserAttributeRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<UserAttributeDefinitionResponse> listDefinitions(boolean enabledOnly) {
        return jdbcTemplate.query("""
                select id, key, name, description, type, options, required, validation,
                       placeholder, display_order, enabled, created_at, updated_at
                from user_attribute_definitions
                where deleted_at is null
                  and (:enabledOnly = false or enabled = true)
                order by display_order asc, id asc
                """, new MapSqlParameterSource("enabledOnly", enabledOnly), (rs, rowNum) ->
                new UserAttributeDefinitionResponse(
                        rs.getLong("id"),
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("type"),
                        jsonHelper.readList(rs.getString("options"), UserAttributeOption.class),
                        rs.getBoolean("required"),
                        readValidation(rs.getString("validation")),
                        rs.getString("placeholder"),
                        rs.getInt("display_order"),
                        rs.getBoolean("enabled"),
                        toIso(rs.getTimestamp("created_at")),
                        toIso(rs.getTimestamp("updated_at"))
                ));
    }

    public Optional<UserAttributeDefinitionResponse> findDefinitionById(long id) {
        List<UserAttributeDefinitionResponse> rows = jdbcTemplate.query("""
                select id, key, name, description, type, options, required, validation,
                       placeholder, display_order, enabled, created_at, updated_at
                from user_attribute_definitions
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id), (rs, rowNum) ->
                new UserAttributeDefinitionResponse(
                        rs.getLong("id"),
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("type"),
                        jsonHelper.readList(rs.getString("options"), UserAttributeOption.class),
                        rs.getBoolean("required"),
                        readValidation(rs.getString("validation")),
                        rs.getString("placeholder"),
                        rs.getInt("display_order"),
                        rs.getBoolean("enabled"),
                        toIso(rs.getTimestamp("created_at")),
                        toIso(rs.getTimestamp("updated_at"))
                ));
        return rows.stream().findFirst();
    }

    public boolean existsActiveDefinitionKey(String key, Long excludeId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("key", key);
        String sql = """
                select count(*)
                from user_attribute_definitions
                where key = :key
                  and deleted_at is null
                """;
        if (excludeId != null) {
            sql += "\n  and id <> :excludeId";
            params.addValue("excludeId", excludeId);
        }
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }

    public long createDefinition(String key,
                                 String name,
                                 String description,
                                 String type,
                                 List<UserAttributeOption> options,
                                 boolean required,
                                 UserAttributeValidation validation,
                                 String placeholder,
                                 boolean enabled) {
        Integer nextDisplayOrder = jdbcTemplate.queryForObject("""
                select coalesce(max(display_order), -1) + 1
                from user_attribute_definitions
                where deleted_at is null
                """, new MapSqlParameterSource(), Integer.class);
        Long id = jdbcTemplate.queryForObject("""
                insert into user_attribute_definitions (
                    key, name, description, type, options, required, validation, placeholder,
                    display_order, enabled, created_at, updated_at
                ) values (
                    :key, :name, :description, :type, cast(:options as jsonb), :required, cast(:validation as jsonb), :placeholder,
                    :displayOrder, :enabled, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("type", type)
                .addValue("options", jsonHelper.writeJson(options == null ? List.of() : options))
                .addValue("required", required)
                .addValue("validation", jsonHelper.writeJson(validation == null ? new UserAttributeValidation(null, null, null, null, null, null) : validation))
                .addValue("placeholder", placeholder)
                .addValue("displayOrder", nextDisplayOrder == null ? 0 : nextDisplayOrder)
                .addValue("enabled", enabled), Long.class);
        if (id == null) {
            throw new IllegalStateException("failed to create attribute definition");
        }
        return id;
    }

    public boolean updateDefinition(long id,
                                    String name,
                                    String description,
                                    String type,
                                    List<UserAttributeOption> options,
                                    boolean required,
                                    UserAttributeValidation validation,
                                    String placeholder,
                                    boolean enabled) {
        int updated = jdbcTemplate.update("""
                update user_attribute_definitions
                set name = :name,
                    description = :description,
                    type = :type,
                    options = cast(:options as jsonb),
                    required = :required,
                    validation = cast(:validation as jsonb),
                    placeholder = :placeholder,
                    enabled = :enabled,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("type", type)
                .addValue("options", jsonHelper.writeJson(options == null ? List.of() : options))
                .addValue("required", required)
                .addValue("validation", jsonHelper.writeJson(validation == null ? new UserAttributeValidation(null, null, null, null, null, null) : validation))
                .addValue("placeholder", placeholder)
                .addValue("enabled", enabled));
        return updated > 0;
    }

    public void deleteValuesByAttributeId(long attributeId) {
        jdbcTemplate.update("""
                delete from user_attribute_values
                where attribute_id = :attributeId
                """, new MapSqlParameterSource("attributeId", attributeId));
    }

    public boolean softDeleteDefinition(long id) {
        int updated = jdbcTemplate.update("""
                update user_attribute_definitions
                set deleted_at = now(), updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
        return updated > 0;
    }

    public void reorderDefinitions(List<Long> ids) {
        int order = 0;
        for (Long id : ids) {
            if (id == null || id <= 0) {
                continue;
            }
            jdbcTemplate.update("""
                    update user_attribute_definitions
                    set display_order = :displayOrder, updated_at = now()
                    where id = :id and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("displayOrder", order++)
                    .addValue("id", id));
        }
    }

    public List<UserAttributeValueResponse> listUserValues(long userId) {
        return jdbcTemplate.query("""
                select id, user_id, attribute_id, value, created_at, updated_at
                from user_attribute_values
                where user_id = :userId
                order by attribute_id asc, id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) ->
                new UserAttributeValueResponse(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getLong("attribute_id"),
                        rs.getString("value"),
                        toIso(rs.getTimestamp("created_at")),
                        toIso(rs.getTimestamp("updated_at"))
                ));
    }

    public Map<Long, Map<Long, String>> batchUserValues(List<Long> userIds) {
        Map<Long, Map<Long, String>> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        jdbcTemplate.query("""
                select user_id, attribute_id, value
                from user_attribute_values
                where user_id in (:userIds)
                order by user_id asc, attribute_id asc
                """, new MapSqlParameterSource("userIds", userIds), rs -> {
            long userId = rs.getLong("user_id");
            long attributeId = rs.getLong("attribute_id");
            result.computeIfAbsent(userId, ignored -> new LinkedHashMap<>())
                    .put(attributeId, rs.getString("value"));
        });
        return result;
    }

    public void upsertUserValue(long userId, long attributeId, String value) {
        jdbcTemplate.update("""
                insert into user_attribute_values (user_id, attribute_id, value, created_at, updated_at)
                values (:userId, :attributeId, :value, now(), now())
                on conflict (user_id, attribute_id)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("attributeId", attributeId)
                .addValue("value", value == null ? "" : value));
    }

    public boolean userExists(long userId) {
        Long id = jdbcTemplate.query("""
                select id from users where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), rs -> rs.next() ? rs.getLong("id") : null);
        return id != null;
    }

    public UserAttributeValidation readValidation(String raw) {
        UserAttributeValidation validation = jsonHelper.readObject(raw, UserAttributeValidation.class);
        return validation == null ? new UserAttributeValidation(null, null, null, null, null, null) : validation;
    }

    private String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
