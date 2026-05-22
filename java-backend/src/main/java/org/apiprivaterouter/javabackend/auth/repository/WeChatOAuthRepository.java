package org.apiprivaterouter.javabackend.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class WeChatOAuthRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public WeChatOAuthRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<AuthIdentityRecord> findCompatibleIdentitiesBySubject(String providerSubject) {
        return jdbcTemplate.query("""
                select id,
                       user_id,
                       provider_type,
                       provider_key,
                       provider_subject,
                       issuer,
                       metadata::text as metadata_json,
                       verified_at
                from auth_identities
                where provider_type = 'wechat'
                  and provider_key in ('wechat-main', 'wechat')
                  and provider_subject = :providerSubject
                order by id asc
                """, new MapSqlParameterSource("providerSubject", trimToEmpty(providerSubject)), (rs, rowNum) -> new AuthIdentityRecord(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getString("issuer"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                toInstant(rs.getObject("verified_at", OffsetDateTime.class))
        ));
    }

    public List<AuthIdentityChannelRecord> findCompatibleChannels(String channel, String channelAppId, String channelSubject) {
        return jdbcTemplate.query("""
                select channel.id,
                       channel.identity_id,
                       channel.provider_type,
                       channel.provider_key,
                       channel.channel,
                       channel.channel_app_id,
                       channel.channel_subject,
                       channel.metadata::text as metadata_json,
                       identity.user_id
                from auth_identity_channels channel
                join auth_identities identity on identity.id = channel.identity_id
                where channel.provider_type = 'wechat'
                  and channel.provider_key in ('wechat-main', 'wechat')
                  and channel.channel = :channel
                  and channel.channel_app_id = :channelAppId
                  and channel.channel_subject = :channelSubject
                order by channel.id asc
                """, new MapSqlParameterSource()
                .addValue("channel", trimToEmpty(channel))
                .addValue("channelAppId", trimToEmpty(channelAppId))
                .addValue("channelSubject", trimToEmpty(channelSubject)), (rs, rowNum) -> new AuthIdentityChannelRecord(
                rs.getLong("id"),
                rs.getLong("identity_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("channel"),
                rs.getString("channel_app_id"),
                rs.getString("channel_subject"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                rs.getLong("user_id")
        ));
    }

    public long upsertIdentity(
            long userId,
            String providerKey,
            String providerSubject,
            String issuer,
            Map<String, Object> metadata
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into auth_identities (
                    user_id,
                    provider_type,
                    provider_key,
                    provider_subject,
                    verified_at,
                    issuer,
                    metadata,
                    created_at,
                    updated_at
                ) values (
                    :userId,
                    'wechat',
                    :providerKey,
                    :providerSubject,
                    now(),
                    :issuer,
                    cast(:metadataJson as jsonb),
                    now(),
                    now()
                )
                on conflict (provider_type, provider_key, provider_subject)
                do update set user_id = excluded.user_id,
                              verified_at = now(),
                              issuer = excluded.issuer,
                              metadata = excluded.metadata,
                              updated_at = now()
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("providerKey", trimToEmpty(providerKey))
                .addValue("providerSubject", trimToEmpty(providerSubject))
                .addValue("issuer", trimToNull(issuer))
                .addValue("metadataJson", jsonHelper.writeJson(copyMap(metadata))), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to upsert wechat auth identity");
        }
        return key.longValue();
    }

    public void updateIdentity(
            long identityId,
            long userId,
            String providerKey,
            String providerSubject,
            String issuer,
            Map<String, Object> metadata
    ) {
        jdbcTemplate.update("""
                update auth_identities
                set user_id = :userId,
                    provider_key = :providerKey,
                    provider_subject = :providerSubject,
                    issuer = :issuer,
                    metadata = cast(:metadataJson as jsonb),
                    verified_at = now(),
                    updated_at = now()
                where id = :identityId
                """, new MapSqlParameterSource()
                .addValue("identityId", identityId)
                .addValue("userId", userId)
                .addValue("providerKey", trimToEmpty(providerKey))
                .addValue("providerSubject", trimToEmpty(providerSubject))
                .addValue("issuer", trimToNull(issuer))
                .addValue("metadataJson", jsonHelper.writeJson(copyMap(metadata))));
    }

    public void upsertIdentityChannel(
            long identityId,
            String providerKey,
            String channel,
            String channelAppId,
            String channelSubject,
            Map<String, Object> metadata
    ) {
        jdbcTemplate.update("""
                insert into auth_identity_channels (
                    identity_id,
                    provider_type,
                    provider_key,
                    channel,
                    channel_app_id,
                    channel_subject,
                    metadata,
                    created_at,
                    updated_at
                ) values (
                    :identityId,
                    'wechat',
                    :providerKey,
                    :channel,
                    :channelAppId,
                    :channelSubject,
                    cast(:metadataJson as jsonb),
                    now(),
                    now()
                )
                on conflict (provider_type, provider_key, channel, channel_app_id, channel_subject)
                do update set identity_id = excluded.identity_id,
                              metadata = excluded.metadata,
                              updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("identityId", identityId)
                .addValue("providerKey", trimToEmpty(providerKey))
                .addValue("channel", trimToEmpty(channel))
                .addValue("channelAppId", trimToEmpty(channelAppId))
                .addValue("channelSubject", trimToEmpty(channelSubject))
                .addValue("metadataJson", jsonHelper.writeJson(copyMap(metadata))));
    }

    public void updateIdentityChannel(
            long channelId,
            long identityId,
            String providerKey,
            Map<String, Object> metadata
    ) {
        jdbcTemplate.update("""
                update auth_identity_channels
                set identity_id = :identityId,
                    provider_key = :providerKey,
                    metadata = cast(:metadataJson as jsonb),
                    updated_at = now()
                where id = :channelId
                """, new MapSqlParameterSource()
                .addValue("channelId", channelId)
                .addValue("identityId", identityId)
                .addValue("providerKey", trimToEmpty(providerKey))
                .addValue("metadataJson", jsonHelper.writeJson(copyMap(metadata))));
    }

    public WeChatOwnerResolution resolveSingleIdentityOwner(List<AuthIdentityRecord> identities) {
        Long resolvedUserId = null;
        AuthIdentityRecord preferred = null;
        AuthIdentityRecord fallback = null;
        boolean hasCanonicalKey = false;
        for (AuthIdentityRecord record : safeList(identities)) {
            if (record == null) {
                continue;
            }
            if (resolvedUserId == null) {
                resolvedUserId = record.userId();
            } else if (resolvedUserId.longValue() != record.userId()) {
                return new WeChatOwnerResolution(null, false, true);
            }
            if ("wechat-main".equalsIgnoreCase(trimToEmpty(record.providerKey()))) {
                hasCanonicalKey = true;
                if (preferred == null) {
                    preferred = record;
                }
            } else if (fallback == null) {
                fallback = record;
            }
        }
        return new WeChatOwnerResolution(preferred != null ? preferred : fallback, hasCanonicalKey, false);
    }

    public WeChatChannelOwnerResolution resolveSingleChannelOwner(List<AuthIdentityChannelRecord> channels) {
        Long resolvedUserId = null;
        AuthIdentityChannelRecord preferred = null;
        AuthIdentityChannelRecord fallback = null;
        boolean hasCanonicalKey = false;
        for (AuthIdentityChannelRecord record : safeList(channels)) {
            if (record == null) {
                continue;
            }
            if (resolvedUserId == null) {
                resolvedUserId = record.userId();
            } else if (resolvedUserId.longValue() != record.userId()) {
                return new WeChatChannelOwnerResolution(null, false, true);
            }
            if ("wechat-main".equalsIgnoreCase(trimToEmpty(record.providerKey()))) {
                hasCanonicalKey = true;
                if (preferred == null) {
                    preferred = record;
                }
            } else if (fallback == null) {
                fallback = record;
            }
        }
        return new WeChatChannelOwnerResolution(preferred != null ? preferred : fallback, hasCanonicalKey, false);
    }

    private Map<String, Object> copyMap(Map<String, Object> values) {
        LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
        if (values != null) {
            copied.putAll(values);
        }
        return copied;
    }

    private Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }

    public record AuthIdentityRecord(
            long id,
            long userId,
            String providerType,
            String providerKey,
            String providerSubject,
            String issuer,
            Map<String, Object> metadata,
            Instant verifiedAt
    ) {
    }

    public record AuthIdentityChannelRecord(
            long id,
            long identityId,
            String providerType,
            String providerKey,
            String channel,
            String channelAppId,
            String channelSubject,
            Map<String, Object> metadata,
            long userId
    ) {
    }

    public record WeChatOwnerResolution(
            AuthIdentityRecord record,
            boolean hasCanonicalKey,
            boolean conflict
    ) {
    }

    public record WeChatChannelOwnerResolution(
            AuthIdentityChannelRecord record,
            boolean hasCanonicalKey,
            boolean conflict
    ) {
    }
}
