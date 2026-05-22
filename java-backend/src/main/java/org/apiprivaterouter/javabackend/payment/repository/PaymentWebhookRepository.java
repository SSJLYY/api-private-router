package org.apiprivaterouter.javabackend.payment.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookCandidate;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookOrder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PaymentWebhookRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public PaymentWebhookRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<PaymentWebhookOrder> findOrderByOutTradeNo(String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return Optional.empty();
        }
        List<PaymentWebhookOrder> rows = jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, plan_id, subscription_group_id, subscription_days, provider_instance_id,
                       provider_key, recharge_code, payment_trade_no, provider_snapshot, updated_at, paid_at, completed_at
                from payment_orders
                where out_trade_no = :outTradeNo
                limit 1
                """, new MapSqlParameterSource("outTradeNo", outTradeNo), (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public Optional<PaymentWebhookOrder> findOrderById(long orderId) {
        List<PaymentWebhookOrder> rows = jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, plan_id, subscription_group_id, subscription_days, provider_instance_id,
                       provider_key, recharge_code, payment_trade_no, provider_snapshot, updated_at, paid_at, completed_at
                from payment_orders
                where id = :id
                limit 1
                """, new MapSqlParameterSource("id", orderId), (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public Optional<PaymentWebhookOrder> findOrderByIdForUpdate(long orderId) {
        List<PaymentWebhookOrder> rows = jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, plan_id, subscription_group_id, subscription_days, provider_instance_id,
                       provider_key, recharge_code, payment_trade_no, provider_snapshot, updated_at, paid_at, completed_at
                from payment_orders
                where id = :id
                for update
                """, new MapSqlParameterSource("id", orderId), (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public List<PaymentWebhookCandidate> findEnabledCandidatesByProviderKey(String providerKey) {
        return jdbcTemplate.query("""
                select id, provider_key, payment_mode, enabled, config
                from payment_provider_instances
                where provider_key = :providerKey and enabled = true
                order by sort_order asc, id asc
                """, new MapSqlParameterSource("providerKey", providerKey), (rs, rowNum) -> mapCandidate(rs));
    }

    public long countEnabledCandidatesByProviderKey(String providerKey) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_provider_instances
                where provider_key = :providerKey and enabled = true
                """, new MapSqlParameterSource("providerKey", providerKey), Long.class);
        return count == null ? 0 : count;
    }

    public Optional<PaymentWebhookCandidate> findCandidateByInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        List<PaymentWebhookCandidate> rows = jdbcTemplate.query("""
                select id, provider_key, payment_mode, enabled, config
                from payment_provider_instances
                where id::text = :id
                limit 1
                """, new MapSqlParameterSource("id", instanceId), (rs, rowNum) -> mapCandidate(rs));
        return rows.stream().findFirst();
    }

    public int markPaid(long orderId, double paidAmount, String tradeNo) {
        return jdbcTemplate.update("""
                update payment_orders
                set status = 'PAID',
                    pay_amount = :paidAmount,
                    payment_trade_no = :tradeNo,
                    paid_at = now(),
                    failed_at = null,
                    failed_reason = null,
                    updated_at = now()
                where id = :orderId
                  and (
                    status = 'PENDING'
                    or status = 'CANCELLED'
                    or (status = 'EXPIRED' and updated_at >= now() - interval '5 minutes')
                  )
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("paidAmount", paidAmount)
                .addValue("tradeNo", tradeNo == null ? "" : tradeNo));
    }

    public int markRecharging(long orderId) {
        return jdbcTemplate.update("""
                update payment_orders
                set status = 'RECHARGING',
                    updated_at = now()
                where id = :orderId and status in ('PAID', 'FAILED')
                """, new MapSqlParameterSource("orderId", orderId));
    }

    public int markCompleted(long orderId) {
        return jdbcTemplate.update("""
                update payment_orders
                set status = 'COMPLETED',
                    completed_at = now(),
                    updated_at = now()
                where id = :orderId and status = 'RECHARGING'
                """, new MapSqlParameterSource("orderId", orderId));
    }

    public int markFailed(long orderId, String failedReason) {
        return jdbcTemplate.update("""
                update payment_orders
                set status = 'FAILED',
                    failed_at = now(),
                    failed_reason = :failedReason,
                    updated_at = now()
                where id = :orderId and status = 'RECHARGING'
                """, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("failedReason", failedReason == null ? "" : failedReason));
    }

    public void insertAuditLog(long orderId, String action, String detailJson, String operator) {
        jdbcTemplate.update("""
                insert into payment_audit_logs (order_id, action, detail, operator, created_at)
                values (:orderId, :action, :detail, :operator, now())
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("orderId", String.valueOf(orderId))
                .addValue("action", action)
                .addValue("detail", detailJson == null ? "" : detailJson)
                .addValue("operator", operator == null || operator.isBlank() ? "system" : operator));
    }

    public boolean claimAffiliateRebateAudit(long orderId, double baseAmount) {
        List<Long> rows = jdbcTemplate.query("""
                insert into payment_audit_logs (order_id, action, detail, operator, created_at)
                select :orderId, 'AFFILIATE_REBATE_APPLIED', :detail, 'system', now()
                where not exists (
                    select 1
                    from payment_audit_logs
                    where order_id = :orderId
                      and action in ('AFFILIATE_REBATE_APPLIED', 'AFFILIATE_REBATE_SKIPPED')
                )
                on conflict (order_id, action) do nothing
                returning id
                """, new MapSqlParameterSource()
                .addValue("orderId", String.valueOf(orderId))
                .addValue("detail", jsonHelper.writeJson(Map.of(
                        "baseAmount", baseAmount,
                        "status", "reserved"
                ))), (rs, rowNum) -> rs.getLong("id"));
        return !rows.isEmpty();
    }

    public void updateAffiliateRebateAudit(long orderId, String action, Map<String, Object> detail) {
        jdbcTemplate.update("""
                update payment_audit_logs
                set action = :action,
                    detail = :detail,
                    operator = 'system'
                where order_id = :orderId
                  and action = 'AFFILIATE_REBATE_APPLIED'
                """, new MapSqlParameterSource()
                .addValue("orderId", String.valueOf(orderId))
                .addValue("action", action)
                .addValue("detail", jsonHelper.writeJson(detail == null ? Map.of() : detail)));
    }

    public Map<String, String> getSettings(String... keys) {
        if (keys == null || keys.length == 0) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select key, value
                from settings
                where key in (:keys)
                """, new MapSqlParameterSource("keys", List.of(keys)));
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("key")), String.valueOf(row.get("value")));
        }
        return result;
    }

    public Optional<AffiliateInviteRow> findAffiliateInviteRowForUpdate(long inviteeUserId) {
        List<AffiliateInviteRow> rows = jdbcTemplate.query("""
                select invitee.user_id as invitee_user_id,
                       invitee.inviter_id,
                       invitee.created_at as invitee_aff_created_at,
                       inviter.aff_rebate_rate_percent
                from user_affiliates invitee
                join user_affiliates inviter on inviter.user_id = invitee.inviter_id
                where invitee.user_id = :inviteeUserId
                  and invitee.inviter_id is not null
                  and invitee.inviter_id > 0
                limit 1
                for update of invitee, inviter
                """, new MapSqlParameterSource("inviteeUserId", inviteeUserId), (rs, rowNum) -> new AffiliateInviteRow(
                rs.getLong("invitee_user_id"),
                rs.getLong("inviter_id"),
                rs.getObject("invitee_aff_created_at", OffsetDateTime.class),
                rs.getObject("aff_rebate_rate_percent", Double.class)
        ));
        return rows.stream().findFirst();
    }

    public double sumAffiliateAccruedFromInvitee(long inviterId, long inviteeUserId) {
        Double total = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0)::double precision
                from user_affiliate_ledger
                where user_id = :inviterId
                  and source_user_id = :inviteeUserId
                  and action = 'accrue'
                """, new MapSqlParameterSource()
                .addValue("inviterId", inviterId)
                .addValue("inviteeUserId", inviteeUserId), Double.class);
        return total == null ? 0.0d : total;
    }

    public boolean accrueAffiliateRebate(long inviterId, long inviteeUserId, long sourceOrderId, double amount, int freezeHours) {
        int updated = jdbcTemplate.update(freezeHours > 0 ? """
                update user_affiliates
                set aff_frozen_quota = aff_frozen_quota + :amount,
                    aff_history_quota = aff_history_quota + :amount,
                    updated_at = now()
                where user_id = :inviterId
                """ : """
                update user_affiliates
                set aff_quota = aff_quota + :amount,
                    aff_history_quota = aff_history_quota + :amount,
                    updated_at = now()
                where user_id = :inviterId
                """, new MapSqlParameterSource()
                .addValue("inviterId", inviterId)
                .addValue("amount", amount));
        if (updated == 0) {
            return false;
        }
        if (freezeHours > 0) {
            jdbcTemplate.update("""
                    insert into user_affiliate_ledger (
                        user_id, action, amount, source_user_id, source_order_id, frozen_until, created_at, updated_at
                    ) values (
                        :inviterId, 'accrue', :amount, :inviteeUserId, :sourceOrderId, now() + make_interval(hours => :freezeHours), now(), now()
                    )
                    """, new MapSqlParameterSource()
                    .addValue("inviterId", inviterId)
                    .addValue("inviteeUserId", inviteeUserId)
                    .addValue("sourceOrderId", sourceOrderId)
                    .addValue("amount", amount)
                    .addValue("freezeHours", freezeHours));
        } else {
            jdbcTemplate.update("""
                    insert into user_affiliate_ledger (
                        user_id, action, amount, source_user_id, source_order_id, created_at, updated_at
                    ) values (
                        :inviterId, 'accrue', :amount, :inviteeUserId, :sourceOrderId, now(), now()
                    )
                    """, new MapSqlParameterSource()
                    .addValue("inviterId", inviterId)
                    .addValue("inviteeUserId", inviteeUserId)
                    .addValue("sourceOrderId", sourceOrderId)
                    .addValue("amount", amount));
        }
        return true;
    }

    public boolean hasAuditLog(long orderId, String action) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from payment_audit_logs
                    where order_id = :orderId and action = :action
                )
                """, new MapSqlParameterSource()
                .addValue("orderId", String.valueOf(orderId))
                .addValue("action", action), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public int ensureRedeemCode(String code, double value, String notes) {
        return jdbcTemplate.update("""
                insert into redeem_codes (code, type, value, status, notes, created_at)
                values (:code, 'balance', :value, 'unused', :notes, now())
                on conflict (code) do nothing
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("value", value)
                .addValue("notes", notes == null ? "" : notes));
    }

    public boolean redeemCodeUsed(String code) {
        Boolean used = jdbcTemplate.queryForObject("""
                select coalesce((select status = 'used' from redeem_codes where code = :code limit 1), false)
                """, new MapSqlParameterSource("code", code), Boolean.class);
        return Boolean.TRUE.equals(used);
    }

    public int markRedeemCodeUsed(String code, long userId) {
        return jdbcTemplate.update("""
                update redeem_codes
                set status = 'used',
                    used_by = :userId,
                    used_at = now()
                where code = :code and status = 'unused'
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("userId", userId));
    }

    public Optional<Double> findUserBalanceForUpdate(long userId) {
        List<Double> rows = jdbcTemplate.query("""
                select balance::double precision
                from users
                where id = :userId and deleted_at is null
                for update
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getDouble(1));
        return rows.stream().findFirst();
    }

    public int addBalance(long userId, double amount) {
        return jdbcTemplate.update("""
                update users
                set balance = balance + :amount,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("amount", amount));
    }

    public boolean subscriptionGroupActive(long groupId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from groups
                    where id = :groupId
                      and deleted_at is null
                      and status = 'active'
                      and subscription_type = 'subscription'
                )
                """, new MapSqlParameterSource("groupId", groupId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<SubscriptionRow> findLatestSubscriptionForUpdate(long userId, long groupId) {
        List<SubscriptionRow> rows = jdbcTemplate.query("""
                select id, starts_at, expires_at, status, notes
                from user_subscriptions
                where user_id = :userId and group_id = :groupId and deleted_at is null
                order by created_at desc
                limit 1
                for update
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), (rs, rowNum) -> new SubscriptionRow(
                rs.getLong("id"),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"),
                rs.getString("notes")
        ));
        return rows.stream().findFirst();
    }

    public long createSubscription(long userId, long groupId, OffsetDateTime startsAt, OffsetDateTime expiresAt, String notes) {
        Long id = jdbcTemplate.query("""
                insert into user_subscriptions (
                    user_id, group_id, starts_at, expires_at, status,
                    daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                    assigned_by, assigned_at, notes, created_at, updated_at
                ) values (
                    :userId, :groupId, :startsAt, :expiresAt, 'active',
                    0, 0, 0,
                    null, now(), :notes, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId)
                .addValue("startsAt", startsAt)
                .addValue("expiresAt", expiresAt)
                .addValue("notes", notes == null ? "" : notes), rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) {
            throw new IllegalStateException("failed to create subscription");
        }
        return id;
    }

    public int updateSubscription(long id, OffsetDateTime expiresAt, String status, String notes) {
        return jdbcTemplate.update("""
                update user_subscriptions
                set expires_at = :expiresAt,
                    status = :status,
                    notes = :notes,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expiresAt", expiresAt)
                .addValue("status", status)
                .addValue("notes", notes == null ? "" : notes));
    }

    private PaymentWebhookCandidate mapCandidate(ResultSet rs) throws SQLException {
        return new PaymentWebhookCandidate(
                rs.getString("id"),
                rs.getString("provider_key"),
                rs.getString("payment_mode"),
                rs.getBoolean("enabled"),
                toStringMap(jsonHelper.readObjectMap(rs.getString("config")))
        );
    }

    private PaymentWebhookOrder mapOrder(ResultSet rs) throws SQLException {
        String providerSnapshotRaw = rs.getString("provider_snapshot");
        return new PaymentWebhookOrder(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getDouble("amount"),
                rs.getDouble("pay_amount"),
                rs.getDouble("fee_rate"),
                rs.getString("payment_type"),
                rs.getString("out_trade_no"),
                rs.getString("status"),
                rs.getString("order_type"),
                rs.getObject("plan_id", Long.class),
                rs.getObject("subscription_group_id", Long.class),
                rs.getObject("subscription_days", Integer.class),
                rs.getString("provider_instance_id"),
                rs.getString("provider_key"),
                rs.getString("recharge_code"),
                rs.getString("payment_trade_no"),
                providerSnapshotRaw,
                jsonHelper.readObjectMap(providerSnapshotRaw),
                toOffsetDateTime(rs.getTimestamp("updated_at")),
                toOffsetDateTime(rs.getTimestamp("paid_at")),
                toOffsetDateTime(rs.getTimestamp("completed_at"))
        );
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(OffsetDateTime.now().getOffset());
    }

    private Map<String, String> toStringMap(Map<String, Object> raw) {
        Map<String, String> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    public record SubscriptionRow(
            long id,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            String status,
            String notes
    ) {
    }

    public record AffiliateInviteRow(
            long inviteeUserId,
            long inviterId,
            OffsetDateTime inviteeAffiliateCreatedAt,
            Double inviterRebateRatePercent
    ) {
    }
}
