package org.apiprivaterouter.javabackend.admin.payment.repository;

import org.apiprivaterouter.javabackend.admin.payment.model.AdminPaymentDashboardResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminPaymentConfigResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.PlanUpsertRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.ProviderUpsertRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.UpdateAdminPaymentConfigRequest;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.payment.model.PaymentChannelResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.SubscriptionPlanResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminPaymentRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminPaymentRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public AdminPaymentConfigResponse loadConfig() {
        Map<String, String> settings = getSettings(
                "payment_enabled",
                "MIN_RECHARGE_AMOUNT",
                "MAX_RECHARGE_AMOUNT",
                "DAILY_RECHARGE_LIMIT",
                "ORDER_TIMEOUT_MINUTES",
                "MAX_PENDING_ORDERS",
                "ENABLED_PAYMENT_TYPES",
                "BALANCE_PAYMENT_DISABLED",
                "BALANCE_RECHARGE_MULTIPLIER",
                "RECHARGE_FEE_RATE",
                "LOAD_BALANCE_STRATEGY",
                "PRODUCT_NAME_PREFIX",
                "PRODUCT_NAME_SUFFIX",
                "PAYMENT_HELP_IMAGE_URL",
                "PAYMENT_HELP_TEXT",
                "STRIPE_PUBLISHABLE_KEY"
        );
        return new AdminPaymentConfigResponse(
                parseBoolean(settings.get("payment_enabled"), true),
                parseDouble(settings.get("MIN_RECHARGE_AMOUNT"), 0),
                parseDouble(settings.get("MAX_RECHARGE_AMOUNT"), 0),
                parseDouble(settings.get("DAILY_RECHARGE_LIMIT"), 0),
                parseInt(settings.get("ORDER_TIMEOUT_MINUTES"), 30),
                parseInt(settings.get("MAX_PENDING_ORDERS"), 3),
                splitCsv(settings.get("ENABLED_PAYMENT_TYPES")),
                parseBoolean(settings.get("BALANCE_PAYMENT_DISABLED"), false),
                parseDouble(settings.get("BALANCE_RECHARGE_MULTIPLIER"), 1),
                parseDouble(settings.get("RECHARGE_FEE_RATE"), 0),
                defaultString(settings.get("LOAD_BALANCE_STRATEGY")),
                defaultString(settings.get("PRODUCT_NAME_PREFIX")),
                defaultString(settings.get("PRODUCT_NAME_SUFFIX")),
                defaultString(settings.get("PAYMENT_HELP_IMAGE_URL")),
                defaultString(settings.get("PAYMENT_HELP_TEXT")),
                defaultString(settings.get("STRIPE_PUBLISHABLE_KEY"))
        );
    }

    public void updateConfig(UpdateAdminPaymentConfigRequest request) {
        Map<String, String> values = new HashMap<>();
        putIfPresent(values, "payment_enabled", request.enabled());
        putIfPresent(values, "MIN_RECHARGE_AMOUNT", request.min_amount());
        putIfPresent(values, "MAX_RECHARGE_AMOUNT", request.max_amount());
        putIfPresent(values, "DAILY_RECHARGE_LIMIT", request.daily_limit());
        putIfPresent(values, "ORDER_TIMEOUT_MINUTES", request.order_timeout_minutes());
        putIfPresent(values, "MAX_PENDING_ORDERS", request.max_pending_orders());
        if (request.enabled_payment_types() != null) {
            values.put("ENABLED_PAYMENT_TYPES", String.join(",", request.enabled_payment_types()));
        }
        putIfPresent(values, "BALANCE_PAYMENT_DISABLED", request.balance_disabled());
        putIfPresent(values, "BALANCE_RECHARGE_MULTIPLIER", request.balance_recharge_multiplier());
        putIfPresent(values, "RECHARGE_FEE_RATE", request.recharge_fee_rate());
        putIfPresent(values, "LOAD_BALANCE_STRATEGY", request.load_balance_strategy());
        putIfPresent(values, "PRODUCT_NAME_PREFIX", request.product_name_prefix());
        putIfPresent(values, "PRODUCT_NAME_SUFFIX", request.product_name_suffix());
        putIfPresent(values, "PAYMENT_HELP_IMAGE_URL", request.help_image_url());
        putIfPresent(values, "PAYMENT_HELP_TEXT", request.help_text());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            jdbcTemplate.update("""
                    insert into settings (key, value, updated_at)
                    values (:key, :value, now())
                    on conflict (key) do update set value = excluded.value, updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("key", entry.getKey())
                    .addValue("value", entry.getValue()));
        }
    }

    public AdminPaymentDashboardResponse loadDashboard(int days) {
        LocalDate startDate = LocalDate.now().minusDays(Math.max(days - 1, 0));
        Double totalAmount = jdbcTemplate.queryForObject("""
                select coalesce(sum(pay_amount), 0)
                from payment_orders
                where status in ('PAID', 'COMPLETED', 'REFUND_REQUESTED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                """, new MapSqlParameterSource(), Double.class);
        Long totalCount = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_orders
                """, new MapSqlParameterSource(), Long.class);
        Double todayAmount = jdbcTemplate.queryForObject("""
                select coalesce(sum(pay_amount), 0)
                from payment_orders
                where created_at >= date_trunc('day', now())
                """, new MapSqlParameterSource(), Double.class);
        Long todayCount = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_orders
                where created_at >= date_trunc('day', now())
                """, new MapSqlParameterSource(), Long.class);
        List<AdminPaymentDashboardResponse.DailySeriesPoint> dailySeries = jdbcTemplate.query("""
                select to_char(date_trunc('day', created_at), 'YYYY-MM-DD') as day,
                       coalesce(sum(pay_amount), 0) as amount,
                       count(*) as count
                from payment_orders
                where created_at >= :startDate
                group by date_trunc('day', created_at)
                order by date_trunc('day', created_at) asc
                """, new MapSqlParameterSource("startDate", startDate), (rs, rowNum) ->
                new AdminPaymentDashboardResponse.DailySeriesPoint(
                        rs.getString("day"),
                        rs.getDouble("amount"),
                        rs.getLong("count")
                ));
        List<AdminPaymentDashboardResponse.PaymentMethodStat> paymentMethods = jdbcTemplate.query("""
                select payment_type, coalesce(sum(pay_amount), 0) as amount, count(*) as count
                from payment_orders
                group by payment_type
                order by amount desc, count desc
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new AdminPaymentDashboardResponse.PaymentMethodStat(
                        rs.getString("payment_type"),
                        rs.getDouble("amount"),
                        rs.getLong("count")
                ));
        List<AdminPaymentDashboardResponse.TopUserStat> topUsers = jdbcTemplate.query("""
                select user_id, max(user_email) as email, coalesce(sum(pay_amount), 0) as amount
                from payment_orders
                group by user_id
                order by amount desc
                limit 10
                """, new MapSqlParameterSource(), (rs, rowNum) ->
                new AdminPaymentDashboardResponse.TopUserStat(
                        rs.getLong("user_id"),
                        defaultString(rs.getString("email")),
                        rs.getDouble("amount")
                ));
        double totalAmountValue = totalAmount == null ? 0 : totalAmount;
        long totalCountValue = totalCount == null ? 0 : totalCount;
        return new AdminPaymentDashboardResponse(
                todayAmount == null ? 0 : todayAmount,
                totalAmountValue,
                todayCount == null ? 0 : todayCount,
                totalCountValue,
                totalCountValue == 0 ? 0 : totalAmountValue / totalCountValue,
                dailySeries,
                paymentMethods,
                topUsers
        );
    }

    public PageResponse<PaymentOrderResponse> listOrders(int page, int pageSize, Long userId, String status, String paymentType, String orderType, String keyword) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String where = """
                where (:userId is null or user_id = :userId)
                  and (:status is null or :status = '' or status = :status)
                  and (:paymentType is null or :paymentType = '' or payment_type = :paymentType)
                  and (:orderType is null or :orderType = '' or order_type = :orderType)
                  and (:keyword is null or :keyword = '' or out_trade_no ilike :likeKeyword or user_email ilike :likeKeyword)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("status", status)
                .addValue("paymentType", paymentType)
                .addValue("orderType", orderType)
                .addValue("keyword", keyword)
                .addValue("likeKeyword", keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%")
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("select count(*) from payment_orders " + where, params, Long.class);
        List<PaymentOrderResponse> items = jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                """ + where + """
                order by created_at desc
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> mapOrder(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<PaymentOrderResponse> getOrder(long id) {
        List<PaymentOrderResponse> rows = jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> getOrderAuditLogs(long orderId) {
        return jdbcTemplate.query("""
                select id, action, operator, detail, created_at
                from payment_audit_logs
                where order_id = :orderId
                order by created_at desc, id desc
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("id", rs.getLong("id"));
            log.put("action", defaultString(rs.getString("action")));
            log.put("operator", defaultString(rs.getString("operator")));
            log.put("actor", defaultString(rs.getString("operator")));
            log.put("detail", defaultString(rs.getString("detail")));
            log.put("created_at", toIsoString(rs.getTimestamp("created_at")));
            return log;
        });
    }

    public Optional<PaymentOrderResponse> cancelOrder(long id) {
        int updated = jdbcTemplate.update("""
                update payment_orders
                set status = 'CANCELLED', updated_at = now()
                where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource("id", id));
        if (updated == 0) {
            return Optional.empty();
        }
        return getOrder(id);
    }

    public Optional<PaymentOrderResponse> retryOrder(long id) {
        jdbcTemplate.update("""
                update payment_orders
                set status = 'PAID', failed_at = null, failed_reason = null, updated_at = now()
                where id = :id and status = 'FAILED'
                """, new MapSqlParameterSource("id", id));
        return getOrder(id);
    }

    public int markRefunding(long id) {
        return jdbcTemplate.update("""
                update payment_orders
                set status = 'REFUNDING', updated_at = now()
                where id = :id and status in ('COMPLETED', 'REFUND_REQUESTED', 'REFUND_FAILED')
                """, new MapSqlParameterSource("id", id));
    }

    public Optional<PaymentOrderResponse> markRefundSucceeded(long id, double amount, String reason, boolean force) {
        jdbcTemplate.update("""
                update payment_orders
                set status = case when :amount < amount then 'PARTIALLY_REFUNDED' else 'REFUNDED' end,
                    refund_amount = :amount,
                    refund_reason = :reason,
                    refund_at = now(),
                    force_refund = :force,
                    updated_at = now()
                where id = :id and status = 'REFUNDING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("amount", amount)
                .addValue("reason", reason)
                .addValue("force", force));
        return getOrder(id);
    }

    public void markRefundFailed(long id, String reason) {
        jdbcTemplate.update("""
                update payment_orders
                set status = 'REFUND_FAILED',
                    failed_at = now(),
                    failed_reason = :reason,
                    updated_at = now()
                where id = :id and status = 'REFUNDING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reason", reason == null ? "" : reason));
    }

    public void restoreRefundStatus(long id, String previousStatus) {
        String status = "REFUND_REQUESTED".equalsIgnoreCase(previousStatus) ? "REFUND_REQUESTED" : "COMPLETED";
        jdbcTemplate.update("""
                update payment_orders
                set status = :status, updated_at = now()
                where id = :id and status = 'REFUNDING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status));
    }

    public Optional<PaymentOrderResponse> getRefundableOrder(long id) {
        List<PaymentOrderResponse> rows = jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where id = :id
                  and status in ('COMPLETED', 'REFUND_REQUESTED', 'REFUND_FAILED')
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public Optional<Long> getSubscriptionGroupId(long orderId) {
        List<Long> rows = jdbcTemplate.query("""
                select subscription_group_id
                from payment_orders
                where id = :orderId and subscription_group_id is not null
                limit 1
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> rs.getLong(1));
        return rows.stream().findFirst();
    }

    public Optional<Integer> getSubscriptionDays(long orderId) {
        List<Integer> rows = jdbcTemplate.query("""
                select subscription_days
                from payment_orders
                where id = :orderId and subscription_days is not null
                limit 1
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) -> rs.getInt(1));
        return rows.stream().findFirst();
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

    public void deductUserBalance(long userId, double amount) {
        int updated = jdbcTemplate.update("""
                update users
                set balance = balance - :amount,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("amount", amount));
        if (updated == 0) {
            throw new IllegalStateException("user not found");
        }
    }

    public void addUserBalance(long userId, double amount) {
        int updated = jdbcTemplate.update("""
                update users
                set balance = balance + :amount,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("amount", amount));
        if (updated == 0) {
            throw new IllegalStateException("user not found");
        }
    }

    public Optional<SubscriptionRefundTarget> findActiveSubscriptionForRefund(long userId, long groupId) {
        List<SubscriptionRefundTarget> rows = jdbcTemplate.query("""
                select id, starts_at, expires_at, status, notes
                from user_subscriptions
                where user_id = :userId
                  and group_id = :groupId
                  and deleted_at is null
                  and status = 'active'
                  and expires_at > now()
                order by expires_at desc, id desc
                limit 1
                for update
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), (rs, rowNum) -> new SubscriptionRefundTarget(
                rs.getLong("id"),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"),
                defaultString(rs.getString("notes"))
        ));
        return rows.stream().findFirst();
    }

    public boolean deductSubscriptionDays(long subscriptionId, int days) {
        int updated = jdbcTemplate.update("""
                update user_subscriptions
                set expires_at = expires_at - make_interval(days => :days),
                    updated_at = now()
                where id = :subscriptionId
                  and deleted_at is null
                  and expires_at - make_interval(days => :days) > now()
                """, new MapSqlParameterSource()
                .addValue("subscriptionId", subscriptionId)
                .addValue("days", days));
        if (updated > 0) {
            return false;
        }
        if (!subscriptionExists(subscriptionId)) {
            throw new IllegalStateException("subscription not found");
        }
        revokeSubscription(subscriptionId);
        return true;
    }

    public void restoreSubscriptionDays(long subscriptionId, int days) {
        int updated = jdbcTemplate.update("""
                update user_subscriptions
                set expires_at = expires_at + make_interval(days => :days),
                    status = 'active',
                    updated_at = now()
                where id = :subscriptionId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("subscriptionId", subscriptionId)
                .addValue("days", days));
        if (updated == 0) {
            throw new IllegalStateException("subscription not found");
        }
    }

    public void revokeSubscription(long subscriptionId) {
        int updated = jdbcTemplate.update("""
                update user_subscriptions
                set deleted_at = now(),
                    updated_at = now()
                where id = :subscriptionId and deleted_at is null
                """, new MapSqlParameterSource("subscriptionId", subscriptionId));
        if (updated == 0) {
            throw new IllegalStateException("subscription not found");
        }
    }

    public void restoreRevokedSubscription(long subscriptionId) {
        int updated = jdbcTemplate.update("""
                update user_subscriptions
                set deleted_at = null,
                    status = 'active',
                    updated_at = now()
                where id = :subscriptionId and deleted_at is not null
                """, new MapSqlParameterSource("subscriptionId", subscriptionId));
        if (updated == 0) {
            throw new IllegalStateException("subscription not found");
        }
    }

    public void insertAuditLog(long orderId, String action, Map<String, Object> detail, String operator) {
        jdbcTemplate.update("""
                insert into payment_audit_logs (order_id, action, detail, operator, created_at)
                values (:orderId, :action, :detail, :operator, now())
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("orderId", String.valueOf(orderId))
                .addValue("action", action)
                .addValue("detail", detail == null ? "{}" : jsonHelper.writeJson(detail))
                .addValue("operator", operator == null || operator.isBlank() ? "admin" : operator));
    }

    public Map<String, Object> loadProviderSnapshot(long orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select provider_snapshot::text as provider_snapshot
                from payment_orders
                where id = :orderId
                limit 1
                """, new MapSqlParameterSource("orderId", orderId), (rs, rowNum) ->
                jsonHelper.readObjectMap(rs.getString("provider_snapshot")));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public boolean hasAuditLog(long orderId, String action) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from payment_audit_logs
                    where order_id = :orderId and action = :action
                )
                """, new MapSqlParameterSource()
                .addValue("orderId", String.valueOf(orderId))
                .addValue("action", action), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private boolean subscriptionExists(long subscriptionId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from user_subscriptions
                    where id = :subscriptionId and deleted_at is null
                )
                """, new MapSqlParameterSource("subscriptionId", subscriptionId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<SubscriptionPlanResponse> listPlans() {
        return jdbcTemplate.query("""
                select sp.id, sp.group_id, sp.name, sp.description, sp.price, sp.original_price, sp.validity_days,
                       sp.validity_unit, sp.features, sp.product_name, sp.for_sale, sp.sort_order,
                       g.platform as group_platform, g.name as group_name, g.rate_multiplier,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd, g.supported_model_scopes
                from subscription_plans sp
                left join groups g on g.id = sp.group_id
                order by sp.sort_order asc, sp.id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> new SubscriptionPlanResponse(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getString("group_platform"),
                rs.getString("group_name"),
                rs.getObject("rate_multiplier", Double.class),
                rs.getObject("daily_limit_usd", Double.class),
                rs.getObject("weekly_limit_usd", Double.class),
                rs.getObject("monthly_limit_usd", Double.class),
                splitCsv(rs.getString("supported_model_scopes")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getDouble("price"),
                rs.getObject("original_price", Double.class),
                rs.getInt("validity_days"),
                rs.getString("validity_unit"),
                splitLines(rs.getString("features")),
                rs.getString("product_name"),
                rs.getBoolean("for_sale"),
                rs.getInt("sort_order")
        ));
    }

    public SubscriptionPlanResponse createPlan(PlanUpsertRequest request) {
        Long id = jdbcTemplate.query("""
                insert into subscription_plans (
                    group_id, name, description, price, original_price, validity_days, validity_unit,
                    features, product_name, for_sale, sort_order, created_at, updated_at
                ) values (
                    :groupId, :name, :description, :price, :originalPrice, :validityDays, :validityUnit,
                    :features, :productName, :forSale, :sortOrder, now(), now()
                )
                returning id
                """, planParams(request), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create plan");
        }
        return getPlan(id).orElseThrow(() -> new IllegalArgumentException("plan not found"));
    }

    public SubscriptionPlanResponse updatePlan(long id, PlanUpsertRequest request) {
        jdbcTemplate.update("""
                update subscription_plans
                set group_id = coalesce(:groupId, group_id),
                    name = coalesce(:name, name),
                    description = coalesce(:description, description),
                    price = coalesce(:price, price),
                    original_price = :originalPrice,
                    validity_days = coalesce(:validityDays, validity_days),
                    validity_unit = coalesce(:validityUnit, validity_unit),
                    features = coalesce(:features, features),
                    product_name = coalesce(:productName, product_name),
                    for_sale = coalesce(:forSale, for_sale),
                    sort_order = coalesce(:sortOrder, sort_order),
                    updated_at = now()
                where id = :id
                """, planParams(request).addValue("id", id));
        return getPlan(id).orElseThrow(() -> new IllegalArgumentException("plan not found"));
    }

    public void deletePlan(long id) {
        jdbcTemplate.update("delete from subscription_plans where id = :id", new MapSqlParameterSource("id", id));
    }

    public List<ProviderInstanceResponse> listProviders() {
        return jdbcTemplate.query("""
                select id, provider_key, name, config, supported_types, enabled, payment_mode,
                       refund_enabled, allow_user_refund, limits, sort_order
                from payment_provider_instances
                order by sort_order asc, id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> new ProviderInstanceResponse(
                rs.getLong("id"),
                rs.getString("provider_key"),
                rs.getString("name"),
                toStringMap(jsonHelper.readObjectMap(rs.getString("config"))),
                splitCsv(rs.getString("supported_types")),
                rs.getBoolean("enabled"),
                rs.getString("payment_mode"),
                rs.getBoolean("refund_enabled"),
                rs.getBoolean("allow_user_refund"),
                rs.getString("limits"),
                rs.getInt("sort_order")
        ));
    }

    public ProviderInstanceResponse createProvider(ProviderUpsertRequest request) {
        Long id = jdbcTemplate.query("""
                insert into payment_provider_instances (
                    provider_key, name, config, supported_types, enabled, payment_mode,
                    sort_order, limits, refund_enabled, allow_user_refund, created_at, updated_at
                ) values (
                    :providerKey, :name, :config, :supportedTypes, :enabled, :paymentMode,
                    :sortOrder, :limits, :refundEnabled, :allowUserRefund, now(), now()
                )
                returning id
                """, providerParams(request), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create provider");
        }
        return getProviderForAdmin(id).orElseThrow(() -> new IllegalArgumentException("provider not found"));
    }

    public ProviderInstanceResponse updateProvider(long id, ProviderUpsertRequest request) {
        jdbcTemplate.update("""
                update payment_provider_instances
                set name = coalesce(:name, name),
                    config = coalesce(:config, config),
                    supported_types = coalesce(:supportedTypes, supported_types),
                    enabled = coalesce(:enabled, enabled),
                    payment_mode = coalesce(:paymentMode, payment_mode),
                    sort_order = coalesce(:sortOrder, sort_order),
                    limits = coalesce(:limits, limits),
                    refund_enabled = coalesce(:refundEnabled, refund_enabled),
                    allow_user_refund = coalesce(:allowUserRefund, allow_user_refund),
                    updated_at = now()
                where id = :id
                """, providerParams(request).addValue("id", id));
        return getProvider(id).orElseThrow(() -> new IllegalArgumentException("provider not found"));
    }

    public void deleteProvider(long id) {
        jdbcTemplate.update("delete from payment_provider_instances where id = :id", new MapSqlParameterSource("id", id));
    }

    public long countActiveOrdersByProviderId(long providerId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_orders
                where provider_instance_id ~ '^[0-9]+$'
                  and cast(provider_instance_id as bigint) = :providerId
                  and status in ('PENDING', 'PAID', 'RECHARGING', 'COMPLETED', 'REFUND_REQUESTED', 'REFUNDING')
                """, new MapSqlParameterSource("providerId", providerId), Long.class);
        return count == null ? 0 : count;
    }

    public long countOrdersByPlanId(long planId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_orders
                where plan_id = :planId
                  and status in ('PENDING', 'PAID', 'RECHARGING', 'COMPLETED', 'REFUND_REQUESTED', 'REFUNDING')
                """, new MapSqlParameterSource("planId", planId), Long.class);
        return count == null ? 0 : count;
    }

    public List<PaymentChannelResponse> listChannels() {
        return jdbcTemplate.query("""
                select id, name, provider_key, enabled, payment_mode, supported_types, limits
                from payment_provider_instances
                order by sort_order asc, id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> new PaymentChannelResponse(
                rs.getLong("id"),
                null,
                rs.getString("name"),
                rs.getString("provider_key"),
                asDouble(jsonHelper.readObjectMap(rs.getString("limits")).getOrDefault("rate_multiplier", 1.0)),
                "",
                List.of(),
                splitCsv(rs.getString("supported_types")),
                rs.getBoolean("enabled"),
                rs.getString("provider_key"),
                rs.getString("payment_mode")
        ));
    }

    public PaymentChannelResponse createChannel(ProviderUpsertRequest request) {
        ProviderInstanceResponse provider = createProvider(request);
        return mapProviderToChannel(provider);
    }

    public PaymentChannelResponse updateChannel(long id, ProviderUpsertRequest request) {
        ProviderInstanceResponse provider = updateProvider(id, request);
        return mapProviderToChannel(provider);
    }

    public void deleteChannel(long id) {
        deleteProvider(id);
    }

    private PaymentOrderResponse mapOrder(ResultSet rs) throws SQLException {
        return new PaymentOrderResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getDouble("amount"),
                rs.getDouble("pay_amount"),
                rs.getDouble("fee_rate"),
                rs.getString("payment_type"),
                rs.getString("out_trade_no"),
                rs.getString("status"),
                rs.getString("order_type"),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("expires_at")),
                toIsoString(rs.getTimestamp("paid_at")),
                toIsoString(rs.getTimestamp("completed_at")),
                rs.getDouble("refund_amount"),
                rs.getString("refund_reason"),
                toIsoString(rs.getTimestamp("refund_requested_at")),
                rs.getString("refund_requested_by"),
                rs.getString("refund_request_reason"),
                rs.getObject("plan_id", Long.class),
                rs.getString("provider_instance_id"),
                rs.getString("provider_key"),
                rs.getString("payment_trade_no"),
                rs.getString("pay_url"),
                rs.getString("qr_code")
        );
    }

    private Optional<SubscriptionPlanResponse> getPlan(long id) {
        List<SubscriptionPlanResponse> rows = jdbcTemplate.query("""
                select sp.id, sp.group_id, sp.name, sp.description, sp.price, sp.original_price, sp.validity_days,
                       sp.validity_unit, sp.features, sp.product_name, sp.for_sale, sp.sort_order,
                       g.platform as group_platform, g.name as group_name, g.rate_multiplier,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd, g.supported_model_scopes
                from subscription_plans sp
                left join groups g on g.id = sp.group_id
                where sp.id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> new SubscriptionPlanResponse(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getString("group_platform"),
                rs.getString("group_name"),
                rs.getObject("rate_multiplier", Double.class),
                rs.getObject("daily_limit_usd", Double.class),
                rs.getObject("weekly_limit_usd", Double.class),
                rs.getObject("monthly_limit_usd", Double.class),
                splitCsv(rs.getString("supported_model_scopes")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getDouble("price"),
                rs.getObject("original_price", Double.class),
                rs.getInt("validity_days"),
                rs.getString("validity_unit"),
                splitLines(rs.getString("features")),
                rs.getString("product_name"),
                rs.getBoolean("for_sale"),
                rs.getInt("sort_order")
        ));
        return rows.stream().findFirst();
    }

    private Optional<ProviderInstanceResponse> getProvider(long id) {
        List<ProviderInstanceResponse> rows = jdbcTemplate.query("""
                select id, provider_key, name, config, supported_types, enabled, payment_mode,
                       refund_enabled, allow_user_refund, limits, sort_order
                from payment_provider_instances
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> new ProviderInstanceResponse(
                rs.getLong("id"),
                rs.getString("provider_key"),
                rs.getString("name"),
                toStringMap(jsonHelper.readObjectMap(rs.getString("config"))),
                splitCsv(rs.getString("supported_types")),
                rs.getBoolean("enabled"),
                rs.getString("payment_mode"),
                rs.getBoolean("refund_enabled"),
                rs.getBoolean("allow_user_refund"),
                rs.getString("limits"),
                rs.getInt("sort_order")
        ));
        return rows.stream().findFirst();
    }

    public Optional<ProviderInstanceResponse> getProviderForAdmin(long id) {
        return getProvider(id).map(this::sanitizeProviderResponse);
    }

    public Optional<ProviderInstanceResponse> getProviderByInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        try {
            return getProvider(Long.parseLong(instanceId.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public List<ProviderInstanceResponse> listProvidersForAdmin() {
        return listProviders().stream().map(this::sanitizeProviderResponse).toList();
    }

    private PaymentChannelResponse mapProviderToChannel(ProviderInstanceResponse provider) {
        return new PaymentChannelResponse(
                provider.id(),
                null,
                provider.name(),
                provider.provider_key(),
                asDouble(jsonHelper.readObjectMap(provider.limits()).getOrDefault("rate_multiplier", 1.0)),
                "",
                List.of(),
                provider.supported_types(),
                provider.enabled(),
                provider.provider_key(),
                provider.payment_mode()
        );
    }

    public ProviderUpsertRequest mergeProviderPatch(long id, ProviderUpsertRequest patch) {
        ProviderInstanceResponse existing = getProvider(id).orElseThrow(() -> new IllegalArgumentException("provider not found"));
        Map<String, String> mergedConfig = mergeConfig(existing.config(), patch.config());
        List<String> supportedTypes = patch.supported_types() == null ? existing.supported_types() : patch.supported_types();
        Boolean enabled = patch.enabled() == null ? existing.enabled() : patch.enabled();
        String paymentMode = patch.payment_mode() == null ? existing.payment_mode() : patch.payment_mode();
        Integer sortOrder = patch.sort_order() == null ? existing.sort_order() : patch.sort_order();
        String limits = patch.limits() == null ? existing.limits() : patch.limits();
        Boolean refundEnabled = patch.refund_enabled() == null ? existing.refund_enabled() : patch.refund_enabled();
        Boolean allowUserRefund = patch.allow_user_refund() == null ? existing.allow_user_refund() : patch.allow_user_refund();
        return new ProviderUpsertRequest(
                patch.provider_key() == null ? existing.provider_key() : patch.provider_key(),
                patch.name() == null ? existing.name() : patch.name(),
                mergedConfig,
                supportedTypes,
                enabled,
                paymentMode,
                sortOrder,
                limits,
                refundEnabled,
                allowUserRefund
        );
    }

    private MapSqlParameterSource planParams(PlanUpsertRequest request) {
        return new MapSqlParameterSource()
                .addValue("groupId", request.group_id())
                .addValue("name", request.name())
                .addValue("description", request.description())
                .addValue("price", request.price())
                .addValue("originalPrice", request.original_price())
                .addValue("validityDays", request.validity_days())
                .addValue("validityUnit", request.validity_unit())
                .addValue("features", request.features())
                .addValue("productName", request.product_name() == null ? "" : request.product_name())
                .addValue("forSale", request.for_sale())
                .addValue("sortOrder", request.sort_order());
    }

    private MapSqlParameterSource providerParams(ProviderUpsertRequest request) {
        return new MapSqlParameterSource()
                .addValue("providerKey", request.provider_key())
                .addValue("name", request.name())
                .addValue("config", request.config() == null ? null : jsonHelper.writeJson(request.config()))
                .addValue("supportedTypes", request.supported_types() == null ? null : String.join(",", request.supported_types()))
                .addValue("enabled", request.enabled())
                .addValue("paymentMode", request.payment_mode())
                .addValue("sortOrder", request.sort_order())
                .addValue("limits", request.limits())
                .addValue("refundEnabled", request.refund_enabled())
                .addValue("allowUserRefund", request.allow_user_refund());
    }

    private ProviderInstanceResponse sanitizeProviderResponse(ProviderInstanceResponse provider) {
        return new ProviderInstanceResponse(
                provider.id(),
                provider.provider_key(),
                provider.name(),
                maskSensitiveConfig(provider.provider_key(), provider.config()),
                provider.supported_types(),
                provider.enabled(),
                provider.payment_mode(),
                provider.refund_enabled(),
                provider.allow_user_refund(),
                provider.limits(),
                provider.sort_order()
        );
    }

    private Map<String, String> mergeConfig(Map<String, String> existing, Map<String, String> patch) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (patch == null) {
            return merged;
        }
        patch.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            merged.put(key, trimmed);
        });
        return merged;
    }

    private Map<String, String> maskSensitiveConfig(String providerKey, Map<String, String> raw) {
        Map<String, String> source = raw == null ? Map.of() : raw;
        if (source.isEmpty()) {
            return Map.of();
        }
        List<String> sensitiveKeys = sensitiveKeys(providerKey);
        Map<String, String> masked = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!sensitiveKeys.contains(key)) {
                masked.put(key, value);
            }
        });
        return masked;
    }

    private List<String> sensitiveKeys(String providerKey) {
        String normalized = providerKey == null ? "" : providerKey.trim().toLowerCase();
        List<String> keys = new ArrayList<>();
        switch (normalized) {
            case "easypay" -> keys.add("pkey");
            case "alipay" -> {
                keys.add("privateKey");
                keys.add("publicKey");
            }
            case "wxpay" -> {
                keys.add("privateKey");
                keys.add("apiV3Key");
                keys.add("publicKey");
            }
            case "stripe" -> {
                keys.add("secretKey");
                keys.add("webhookSecret");
            }
            default -> {
            }
        }
        return keys;
    }

    private Map<String, String> getSettings(String... keys) {
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

    private void putIfPresent(Map<String, String> values, String key, Object value) {
        if (value != null) {
            values.put(key, String.valueOf(value));
        }
    }

    private boolean parseBoolean(String raw, boolean defaultValue) {
        return raw == null || raw.isBlank() ? defaultValue : Boolean.parseBoolean(raw);
    }

    private int parseInt(String raw, int defaultValue) {
        try {
            return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private double parseDouble(String raw, double defaultValue) {
        try {
            return raw == null || raw.isBlank() ? defaultValue : Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return raw.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, String> toStringMap(Map<String, Object> raw) {
        Map<String, String> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record SubscriptionRefundTarget(
            long id,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            String status,
            String notes
    ) {
    }
}
