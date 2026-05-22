package org.apiprivaterouter.javabackend.payment.repository;

import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentChannelResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentConfigResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.SubscriptionPlanResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class PaymentRepository {

    private static final String SETTING_PAYMENT_VISIBLE_METHOD_ALIPAY_SOURCE = "payment_visible_method_alipay_source";
    private static final String SETTING_PAYMENT_VISIBLE_METHOD_WXPAY_SOURCE = "payment_visible_method_wxpay_source";
    private static final String SETTING_PAYMENT_VISIBLE_METHOD_ALIPAY_ENABLED = "payment_visible_method_alipay_enabled";
    private static final String SETTING_PAYMENT_VISIBLE_METHOD_WXPAY_ENABLED = "payment_visible_method_wxpay_enabled";
    private static final String SOURCE_OFFICIAL_ALIPAY = "official_alipay";
    private static final String SOURCE_EASYPAY_ALIPAY = "easypay_alipay";
    private static final String SOURCE_STRIPE_ALIPAY = "stripe_alipay";
    private static final String SOURCE_OFFICIAL_WXPAY = "official_wxpay";
    private static final String SOURCE_EASYPAY_WXPAY = "easypay_wxpay";
    private static final String SOURCE_STRIPE_WXPAY = "stripe_wxpay";
    private static final AtomicLong PROVIDER_SELECTION_COUNTER = new AtomicLong();

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public PaymentRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PaymentConfigResponse loadPaymentConfig() {
        Map<String, String> settings = getSettings(
                "PAYMENT_ENABLED",
                "payment_enabled",
                "PAYMENT_MIN_AMOUNT",
                "MIN_RECHARGE_AMOUNT",
                "payment_min_amount",
                "PAYMENT_MAX_AMOUNT",
                "MAX_RECHARGE_AMOUNT",
                "payment_max_amount",
                "PAYMENT_DAILY_LIMIT",
                "DAILY_RECHARGE_LIMIT",
                "payment_daily_limit",
                "PAYMENT_MAX_PENDING_ORDERS",
                "MAX_PENDING_ORDERS",
                "payment_max_pending_orders",
                "PAYMENT_ORDER_TIMEOUT_MINUTES",
                "ORDER_TIMEOUT_MINUTES",
                "payment_order_timeout_minutes",
                "BALANCE_PAYMENT_DISABLED",
                "BALANCE_RECHARGE_MULTIPLIER",
                "payment_balance_recharge_multiplier",
                "ENABLED_PAYMENT_TYPES",
                "payment_enabled_types",
                "RECHARGE_FEE_RATE",
                "payment_recharge_fee_rate",
                "PAYMENT_HELP_TEXT",
                "payment_help_text",
                "PAYMENT_HELP_IMAGE_URL",
                "payment_help_image_url",
                "STRIPE_PUBLISHABLE_KEY"
        );
        return new PaymentConfigResponse(
                parseBoolean(setting(settings, "payment_enabled", "PAYMENT_ENABLED"), false),
                parseDouble(setting(settings, "MIN_RECHARGE_AMOUNT", "payment_min_amount", "PAYMENT_MIN_AMOUNT"), 1.0),
                parseDouble(setting(settings, "MAX_RECHARGE_AMOUNT", "payment_max_amount", "PAYMENT_MAX_AMOUNT"), 0.0),
                parseDouble(setting(settings, "DAILY_RECHARGE_LIMIT", "payment_daily_limit", "PAYMENT_DAILY_LIMIT"), 0.0),
                parseInt(setting(settings, "MAX_PENDING_ORDERS", "payment_max_pending_orders", "PAYMENT_MAX_PENDING_ORDERS"), 3),
                parseInt(setting(settings, "ORDER_TIMEOUT_MINUTES", "payment_order_timeout_minutes", "PAYMENT_ORDER_TIMEOUT_MINUTES"), 30),
                parseBoolean(settings.get("BALANCE_PAYMENT_DISABLED"), false),
                parseDouble(setting(settings, "BALANCE_RECHARGE_MULTIPLIER", "payment_balance_recharge_multiplier"), 1.0),
                parseStringListSetting(setting(settings, "ENABLED_PAYMENT_TYPES", "payment_enabled_types")),
                parseDouble(setting(settings, "RECHARGE_FEE_RATE", "payment_recharge_fee_rate"), 0.0),
                setting(settings, "PAYMENT_HELP_TEXT", "payment_help_text"),
                setting(settings, "PAYMENT_HELP_IMAGE_URL", "payment_help_image_url"),
                settings.getOrDefault("STRIPE_PUBLISHABLE_KEY", "")
        );
    }

    public List<SubscriptionPlanResponse> loadPlansForSale() {
        String sql = """
                select sp.id, sp.group_id, sp.name, sp.description, sp.price, sp.original_price, sp.validity_days,
                       sp.validity_unit, sp.features, sp.product_name, sp.for_sale, sp.sort_order,
                       g.platform as group_platform, g.name as group_name, g.rate_multiplier,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd, g.supported_model_scopes
                from subscription_plans sp
                left join groups g on g.id = sp.group_id
                where sp.for_sale = true
                order by sp.sort_order asc, sp.id asc
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SubscriptionPlanResponse(
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
                splitFeatures(rs.getString("features")),
                rs.getString("product_name"),
                rs.getBoolean("for_sale"),
                rs.getInt("sort_order")
        ));
    }

    public List<PaymentChannelResponse> loadEnabledChannels() {
        String sql = """
                select id, name, provider_key, enabled, payment_mode, supported_types, limits
                from payment_provider_instances
                where enabled = true
                order by sort_order asc, id asc
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PaymentChannelResponse(
                rs.getLong("id"),
                null,
                rs.getString("name"),
                rs.getString("provider_key"),
                parseRateMultiplier(rs.getString("limits")),
                "",
                List.of(),
                parseFeatures(rs.getString("supported_types"), rs.getString("payment_mode")),
                rs.getBoolean("enabled"),
                rs.getString("provider_key"),
                rs.getString("payment_mode")
        ));
    }

    public List<ProviderInstanceResponse> loadProviderInstances() {
        String sql = """
                select id, provider_key, name, config, supported_types, enabled, payment_mode,
                       refund_enabled, allow_user_refund, limits, sort_order
                from payment_provider_instances
                order by sort_order asc, id asc
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ProviderInstanceResponse(
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

    public PageResponse<PaymentOrderResponse> loadOrdersByUser(long userId, int page, int pageSize, String status, String orderType, String paymentType) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String countSql = """
                select count(*)
                from payment_orders
                where user_id = :userId
                  and (:status is null or :status = '' or status = :status)
                  and (:orderType is null or :orderType = '' or order_type = :orderType)
                  and (:paymentType is null or :paymentType = '' or payment_type = :paymentType)
                """;
        String dataSql = """
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where user_id = :userId
                  and (:status is null or :status = '' or status = :status)
                  and (:orderType is null or :orderType = '' or order_type = :orderType)
                  and (:paymentType is null or :paymentType = '' or payment_type = :paymentType)
                order by created_at desc
                limit :pageSize offset :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("status", status)
                .addValue("orderType", orderType)
                .addValue("paymentType", paymentType)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        List<PaymentOrderResponse> items = jdbcTemplate.query(dataSql, params, (rs, rowNum) -> mapOrder(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<PaymentOrderResponse> loadOrderByUserAndId(long userId, long id) {
        String sql = """
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where user_id = :userId and id = :id
                """;
        List<PaymentOrderResponse> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("userId", userId).addValue("id", id),
                (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public Map<String, Object> loadLimitsSnapshot() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select provider_key, supported_types, limits
                from payment_provider_instances
                where enabled = true
                order by sort_order asc, id asc
                """, new MapSqlParameterSource());
        Map<String, String> settings = getSettings(
                SETTING_PAYMENT_VISIBLE_METHOD_ALIPAY_SOURCE,
                SETTING_PAYMENT_VISIBLE_METHOD_WXPAY_SOURCE,
                SETTING_PAYMENT_VISIBLE_METHOD_ALIPAY_ENABLED,
                SETTING_PAYMENT_VISIBLE_METHOD_WXPAY_ENABLED
        );
        Map<String, Object> methods = new HashMap<>();
        double globalMin = 0;
        double globalMax = 0;
        List<Map<String, Object>> stripeRows = new ArrayList<>();
        List<Map<String, Object>> alipayRows = new ArrayList<>();
        List<Map<String, Object>> wxpayRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String providerKey = trimToEmpty((String) row.get("provider_key")).toLowerCase();
            if ("stripe".equals(providerKey)) {
                stripeRows.add(row);
            }
            if (supportsVisibleMethod(providerKey, (String) row.get("supported_types"), "alipay")) {
                alipayRows.add(row);
            }
            if (supportsVisibleMethod(providerKey, (String) row.get("supported_types"), "wxpay")) {
                wxpayRows.add(row);
            }
        }

        Map<String, Object> stripeLimit = aggregateVisibleMethodLimits(stripeRows);
        if (stripeLimit != null) {
            methods.put("stripe", stripeLimit);
            globalMin = mergeGlobalMin(globalMin, asDouble(stripeLimit.get("single_min")));
            globalMax = Math.max(globalMax, asDouble(stripeLimit.get("single_max")));
        }

        Map<String, Object> alipayLimit = aggregateVisibleMethodLimits(selectVisibleMethodRows(
                alipayRows,
                settings,
                "alipay"
        ));
        if (alipayLimit != null && visibleMethodEnabled(settings, "alipay")) {
            methods.put("alipay", alipayLimit);
            globalMin = mergeGlobalMin(globalMin, asDouble(alipayLimit.get("single_min")));
            globalMax = Math.max(globalMax, asDouble(alipayLimit.get("single_max")));
        }

        Map<String, Object> wxpayLimit = aggregateVisibleMethodLimits(selectVisibleMethodRows(
                wxpayRows,
                settings,
                "wxpay"
        ));
        if (wxpayLimit != null && visibleMethodEnabled(settings, "wxpay")) {
            methods.put("wxpay", wxpayLimit);
            globalMin = mergeGlobalMin(globalMin, asDouble(wxpayLimit.get("single_min")));
            globalMax = Math.max(globalMax, asDouble(wxpayLimit.get("single_max")));
        }
        return Map.of("methods", methods, "global_min", globalMin, "global_max", globalMax);
    }

    public PaymentOrderResponse createOrder(
            long userId,
            String userEmail,
            CreateOrderRequest request,
            String paymentType,
            ProviderInstanceResponse provider,
            String payUrl,
            String qrCode,
            String clientIp,
            String srcHost,
            String srcUrl,
            String providerSnapshot,
            Long subscriptionGroupId,
            Integer subscriptionDays,
            double orderAmount,
            double payBaseAmount
    ) {
        String outTradeNo = "JAVA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        double amount = roundAmount(orderAmount);
        String orderType = request.order_type() == null || request.order_type().isBlank() ? "balance" : request.order_type();
        PaymentConfigResponse config = loadPaymentConfig();
        double feeRate = orderType.equals("balance") ? config.recharge_fee_rate() : 0.0;
        double payAmount = roundAmount(payBaseAmount * (1.0 + feeRate));
        int timeoutMinutes = Math.max(config.order_timeout_minutes(), 1);
        String rechargeCode = "PAY-" + outTradeNo;
        String sql = """
                insert into payment_orders (
                    user_id, user_email, user_name, amount, pay_amount, fee_rate, recharge_code,
                    out_trade_no, payment_type, payment_trade_no, order_type, plan_id, status,
                    subscription_group_id, subscription_days, expires_at, client_ip, src_host, src_url,
                    provider_instance_id, provider_key, provider_snapshot, pay_url, qr_code, created_at, updated_at
                ) values (
                    :userId, :userEmail, '', :amount, :payAmount, :feeRate, :rechargeCode,
                    :outTradeNo, :paymentType, '', :orderType, :planId, 'PENDING',
                    :subscriptionGroupId, :subscriptionDays, now() + make_interval(mins => :timeoutMinutes), :clientIp, :srcHost, :srcUrl,
                    :providerInstanceId, :providerKey, cast(:providerSnapshot as jsonb), :payUrl, :qrCode, now(), now()
                )
                returning id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("userEmail", userEmail)
                .addValue("amount", amount)
                .addValue("payAmount", payAmount)
                .addValue("feeRate", feeRate)
                .addValue("rechargeCode", rechargeCode)
                .addValue("outTradeNo", outTradeNo)
                .addValue("paymentType", paymentType)
                .addValue("orderType", orderType)
                .addValue("planId", request.plan_id())
                .addValue("subscriptionGroupId", subscriptionGroupId)
                .addValue("subscriptionDays", subscriptionDays)
                .addValue("timeoutMinutes", timeoutMinutes)
                .addValue("clientIp", defaultString(clientIp))
                .addValue("srcHost", defaultString(srcHost))
                .addValue("srcUrl", blankToNull(srcUrl))
                .addValue("providerInstanceId", String.valueOf(provider.id()))
                .addValue("providerKey", provider.provider_key())
                .addValue("providerSnapshot", providerSnapshot == null || providerSnapshot.isBlank() ? "{}" : providerSnapshot)
                .addValue("payUrl", payUrl)
                .addValue("qrCode", qrCode);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return loadOrderByUserAndId(userId, id).orElseGet(() -> new PaymentOrderResponse(
                id,
                userId,
                amount,
                payAmount,
                feeRate,
                paymentType,
                outTradeNo,
                "PENDING",
                orderType,
                OffsetDateTime.now().toString(),
                OffsetDateTime.now().plusMinutes(timeoutMinutes).toString(),
                null,
                null,
                0.0,
                null,
                null,
                null,
                null,
                request.plan_id(),
                String.valueOf(provider.id()),
                provider.provider_key(),
                "",
                payUrl,
                qrCode
        ));
    }

    public Optional<SubscriptionPlanResponse> findPlanByIdForSale(long planId) {
        String sql = """
                select sp.id, sp.group_id, sp.name, sp.description, sp.price, sp.original_price, sp.validity_days,
                       sp.validity_unit, sp.features, sp.product_name, sp.for_sale, sp.sort_order,
                       g.platform as group_platform, g.name as group_name, g.rate_multiplier,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd, g.supported_model_scopes
                from subscription_plans sp
                join groups g on g.id = sp.group_id
                where sp.id = :planId
                  and sp.for_sale = true
                  and g.deleted_at is null
                  and g.status = 'active'
                  and g.subscription_type = 'subscription'
                limit 1
                """;
        List<SubscriptionPlanResponse> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("planId", planId),
                (rs, rowNum) -> new SubscriptionPlanResponse(
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
                        splitFeatures(rs.getString("features")),
                        rs.getString("product_name"),
                        rs.getBoolean("for_sale"),
                        rs.getInt("sort_order")
                )
        );
        return rows.stream().findFirst();
    }

    public ProviderInstanceResponse resolveCreateProvider(String paymentType) {
        return resolveCreateProvider(paymentType, 0.0);
    }

    public ProviderInstanceResponse resolveCreateProvider(String paymentType, double orderAmount) {
        String normalizedPaymentType = normalizeVisibleMethod(paymentType);
        List<ProviderInstanceResponse> candidates = loadProviderInstances().stream()
                .filter(ProviderInstanceResponse::enabled)
                .filter(provider -> supportsVisibleMethod(
                        provider.provider_key(),
                        String.join(",", provider.supported_types()),
                        normalizedPaymentType
                ))
                .toList();
        if (candidates.isEmpty()) {
            throw new StructuredApiErrorException(503, "PAYMENT_GATEWAY_ERROR", "method_not_configured");
        }
        Map<String, String> settings = getSettings(
                settingKeyForSource(normalizedPaymentType),
                "LOAD_BALANCE_STRATEGY",
                "payment_load_balance_strategy"
        );
        candidates = applyVisibleMethodSource(candidates, normalizedPaymentType, settings);
        Map<Long, Double> dailyUsage = loadProviderDailyUsage(candidates);
        List<ProviderInstanceResponse> available = candidates.stream()
                .filter(provider -> providerCanAcceptAmount(provider, normalizedPaymentType, orderAmount, dailyUsage.getOrDefault(provider.id(), 0.0d)))
                .toList();
        if (available.isEmpty()) {
            available = candidates;
        }
        return selectProviderByStrategy(available, dailyUsage, setting(settings, "LOAD_BALANCE_STRATEGY", "payment_load_balance_strategy"));
    }

    public Optional<PaymentOrderResponse> findOrderByIdPublic(long id) {
        String sql = """
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where id = :id
                """;
        List<PaymentOrderResponse> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("id", id),
                (rs, rowNum) -> mapOrder(rs)
        );
        return rows.stream().findFirst();
    }

    public void updatePaymentRouting(long id, String payUrl, String qrCode, String paymentTradeNo) {
        jdbcTemplate.update("""
                update payment_orders
                set pay_url = :payUrl,
                    qr_code = :qrCode,
                    payment_trade_no = coalesce(:paymentTradeNo, payment_trade_no),
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("payUrl", payUrl)
                .addValue("qrCode", qrCode)
                .addValue("paymentTradeNo", blankToNull(paymentTradeNo)));
    }

    public void markOrderFailed(long id, String reason) {
        jdbcTemplate.update("""
                update payment_orders
                set status = 'FAILED',
                    failed_at = now(),
                    failed_reason = :reason,
                    updated_at = now()
                where id = :id and status = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reason", reason == null ? "" : reason));
    }

    public long countPendingOrders(long userId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_orders
                where user_id = :userId and status = 'PENDING'
                """, new MapSqlParameterSource("userId", userId), Long.class);
        return count == null ? 0 : count;
    }

    public double sumUserPaidAmountToday(long userId) {
        Double total = jdbcTemplate.queryForObject("""
                select coalesce(sum(case when order_type = 'balance' then pay_amount else amount end), 0)
                from payment_orders
                where user_id = :userId
                  and status in ('PAID', 'RECHARGING', 'COMPLETED')
                  and paid_at >= date_trunc('day', now())
                """, new MapSqlParameterSource("userId", userId), Double.class);
        return total == null ? 0.0 : total;
    }

    public boolean userIsActive(long userId) {
        Boolean active = jdbcTemplate.queryForObject("""
                select coalesce((
                    select status = 'active'
                    from users
                    where id = :userId and deleted_at is null
                    limit 1
                ), false)
                """, new MapSqlParameterSource("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    public Optional<PaymentOrderResponse> findOrderByOutTradeNo(long userId, String outTradeNo) {
        String sql = """
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where user_id = :userId and out_trade_no = :outTradeNo
                """;
        List<PaymentOrderResponse> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("userId", userId).addValue("outTradeNo", outTradeNo),
                (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public Optional<PaymentOrderResponse> cancelPendingOrder(long userId, long id) {
        int updated = jdbcTemplate.update("""
                update payment_orders
                set status = 'CANCELLED', updated_at = now()
                where id = :id and user_id = :userId and status = 'PENDING'
                """, new MapSqlParameterSource().addValue("id", id).addValue("userId", userId));
        if (updated == 0) {
            return Optional.empty();
        }
        return loadOrderByUserAndId(userId, id);
    }

    public Optional<Double> findUserBalance(long userId) {
        List<Double> rows = jdbcTemplate.query("""
                select balance::double precision
                from users
                where id = :userId and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getDouble(1));
        return rows.stream().findFirst();
    }

    public Optional<PaymentOrderResponse> markRefundRequested(long userId, long id, String reason, double amount) {
        jdbcTemplate.update("""
                update payment_orders
                set status = 'REFUND_REQUESTED',
                    refund_amount = :amount,
                    refund_requested_at = now(),
                    refund_request_reason = :reason,
                    refund_requested_by = :requestedBy,
                    updated_at = now()
                where id = :id
                  and user_id = :userId
                  and status = 'COMPLETED'
                  and order_type = 'balance'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("requestedBy", String.valueOf(userId))
                .addValue("amount", amount)
                .addValue("reason", reason));
        return loadOrderByUserAndId(userId, id);
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
                .addValue("operator", operator == null || operator.isBlank() ? "system" : operator));
    }

    public List<String> getRefundEligibleProviderIds() {
        return jdbcTemplate.query("""
                select id::text
                from payment_provider_instances
                where refund_enabled = true and allow_user_refund = true
                order by sort_order asc, id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> rs.getString(1));
    }

    public Optional<PaymentOrderResponse> findOrderByOutTradeNoPublic(String outTradeNo) {
        String sql = """
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where out_trade_no = :outTradeNo
                """;
        List<PaymentOrderResponse> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource("outTradeNo", outTradeNo),
                (rs, rowNum) -> mapOrder(rs));
        return rows.stream().findFirst();
    }

    public List<PaymentOrderResponse> findExpiredPendingOrders(int limit) {
        return jdbcTemplate.query("""
                select id, user_id, amount, pay_amount, fee_rate, payment_type, out_trade_no, status,
                       order_type, created_at, expires_at, paid_at, completed_at, refund_amount,
                       refund_reason, refund_requested_at, refund_requested_by, refund_request_reason,
                       plan_id, provider_instance_id, provider_key, payment_trade_no, pay_url, qr_code
                from payment_orders
                where status = 'PENDING'
                  and expires_at <= now()
                order by expires_at asc, id asc
                limit :limit
                """, new MapSqlParameterSource("limit", Math.max(limit, 1)), (rs, rowNum) -> mapOrder(rs));
    }

    public int markOrderExpired(long id) {
        return jdbcTemplate.update("""
                update payment_orders
                set status = 'EXPIRED',
                    updated_at = now()
                where id = :id
                  and status = 'PENDING'
                  and expires_at <= now()
                """, new MapSqlParameterSource("id", id));
    }

    public Optional<ProviderInstanceResponse> findProviderByInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                select id, provider_key, name, config, supported_types, enabled, payment_mode,
                       refund_enabled, allow_user_refund, limits, sort_order
                from payment_provider_instances
                where id::text = :id
                limit 1
                """;
        List<ProviderInstanceResponse> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("id", instanceId.trim()),
                (rs, rowNum) -> new ProviderInstanceResponse(
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
                )
        );
        return rows.stream().findFirst();
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

    private Map<String, String> getSettings(String... keys) {
        String sql = """
                select key, value
                from settings
                where key in (:keys)
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, new MapSqlParameterSource("keys", List.of(keys)));
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("key")), String.valueOf(row.get("value")));
        }
        return result;
    }

    private String setting(Map<String, String> settings, String... keys) {
        for (String key : keys) {
            String value = settings.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private double parseDouble(String raw, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw);
    }

    private List<String> parseStringListSetting(String raw) {
        List<String> fromJson = jsonHelper.readStringList(raw);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        return splitCsv(raw);
    }

    private List<String> splitFeatures(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return raw.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private List<String> parseFeatures(String supportedTypes, String paymentMode) {
        List<String> features = new ArrayList<>(splitCsv(supportedTypes));
        if (paymentMode != null && !paymentMode.isBlank()) {
            features.add("mode:" + paymentMode);
        }
        return features;
    }

    private double parseRateMultiplier(String rawLimits) {
        return asDouble(jsonHelper.readObjectMap(rawLimits).getOrDefault("rate_multiplier", 1.0));
    }

    private Map<String, Object> aggregateVisibleMethodLimits(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        boolean unlimited = false;
        boolean available = false;
        double dailyLimit = 0;
        double dailyUsed = 0;
        double singleMin = 0;
        double singleMax = 0;
        double feeRate = 0;
        boolean minSet = false;
        boolean maxUnlimited = false;
        boolean dailyUnlimited = false;

        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = normalizeLimit(jsonHelper.readObjectMap((String) row.get("limits")));
            if (normalized.isEmpty()) {
                unlimited = true;
                available = true;
                continue;
            }
            available = available || Boolean.TRUE.equals(normalized.get("available"));

            double candidateMin = asDouble(normalized.get("single_min"));
            double candidateMax = asDouble(normalized.get("single_max"));
            double candidateDailyLimit = asDouble(normalized.get("daily_limit"));
            double candidateDailyUsed = asDouble(normalized.get("daily_used"));
            double candidateFeeRate = asDouble(normalized.get("fee_rate"));

            if (!minSet || singleMin <= 0 || (candidateMin > 0 && candidateMin < singleMin)) {
                singleMin = candidateMin;
                minSet = true;
            }
            if (candidateMax <= 0) {
                maxUnlimited = true;
            } else if (!maxUnlimited) {
                singleMax = Math.max(singleMax, candidateMax);
            }
            if (candidateDailyLimit <= 0) {
                dailyUnlimited = true;
            } else if (!dailyUnlimited) {
                dailyLimit = Math.max(dailyLimit, candidateDailyLimit);
            }
            dailyUsed = Math.max(dailyUsed, candidateDailyUsed);
            feeRate = Math.max(feeRate, candidateFeeRate);
        }

        if (unlimited) {
            return Map.of(
                    "daily_limit", 0.0,
                    "daily_used", 0.0,
                    "daily_remaining", 0.0,
                    "single_min", 0.0,
                    "single_max", 0.0,
                    "fee_rate", 0.0,
                    "available", true
            );
        }

        if (maxUnlimited) {
            singleMax = 0;
        }
        if (dailyUnlimited) {
            dailyLimit = 0;
        }

        Map<String, Object> aggregated = new HashMap<>();
        aggregated.put("daily_limit", dailyLimit);
        aggregated.put("daily_used", dailyUsed);
        aggregated.put("daily_remaining", dailyLimit <= 0 ? 0.0 : Math.max(dailyLimit - dailyUsed, 0));
        aggregated.put("single_min", singleMin);
        aggregated.put("single_max", singleMax);
        aggregated.put("fee_rate", feeRate);
        aggregated.put("available", available);
        return aggregated;
    }

    private List<Map<String, Object>> selectVisibleMethodRows(
            List<Map<String, Object>> candidates,
            Map<String, String> settings,
            String method
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String source = normalizeVisibleMethodSource(method, settings.get(settingKeyForSource(method)));
        if (source == null || source.isBlank()) {
            return candidates;
        }
        String providerKey = providerKeyForSource(source);
        if (providerKey == null || providerKey.isBlank()) {
            return candidates;
        }
        List<Map<String, Object>> filtered = candidates.stream()
                .filter(row -> providerKey.equalsIgnoreCase(trimToEmpty((String) row.get("provider_key"))))
                .toList();
        return filtered.isEmpty() ? candidates : filtered;
    }

    private boolean visibleMethodEnabled(Map<String, String> settings, String method) {
        String raw = settings.get(settingKeyForEnabled(method));
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(raw);
    }

    private List<ProviderInstanceResponse> applyVisibleMethodSource(
            List<ProviderInstanceResponse> candidates,
            String normalizedPaymentType,
            Map<String, String> settings
    ) {
        if (!"alipay".equals(normalizedPaymentType) && !"wxpay".equals(normalizedPaymentType)) {
            return candidates;
        }
        String configuredProviderKey = providerKeyForSource(
                normalizeVisibleMethodSource(normalizedPaymentType, settings.get(settingKeyForSource(normalizedPaymentType)))
        );
        if (configuredProviderKey == null || configuredProviderKey.isBlank()) {
            return candidates;
        }
        List<ProviderInstanceResponse> filtered = candidates.stream()
                .filter(candidate -> configuredProviderKey.equalsIgnoreCase(trimToEmpty(candidate.provider_key())))
                .toList();
        return filtered.isEmpty() ? candidates : filtered;
    }

    private Map<Long, Double> loadProviderDailyUsage(List<ProviderInstanceResponse> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<String> instanceIds = candidates.stream().map(provider -> String.valueOf(provider.id())).toList();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select provider_instance_id, coalesce(sum(pay_amount), 0)::double precision as daily_used
                from payment_orders
                where provider_instance_id in (:instanceIds)
                  and status in ('PENDING', 'PAID', 'COMPLETED', 'RECHARGING')
                  and created_at >= date_trunc('day', now())
                group by provider_instance_id
                """, new MapSqlParameterSource("instanceIds", instanceIds));
        Map<Long, Double> usage = new HashMap<>();
        for (Map<String, Object> row : rows) {
            try {
                usage.put(Long.parseLong(String.valueOf(row.get("provider_instance_id"))), asDouble(row.get("daily_used")));
            } catch (NumberFormatException ignored) {
                // Ignore legacy non-numeric instance bindings.
            }
        }
        return usage;
    }

    private ProviderInstanceResponse selectProviderByStrategy(
            List<ProviderInstanceResponse> candidates,
            Map<Long, Double> dailyUsage,
            String strategy
    ) {
        if (candidates == null || candidates.isEmpty()) {
            throw new StructuredApiErrorException(503, "PAYMENT_GATEWAY_ERROR", "method_not_configured");
        }
        if ("least-amount".equalsIgnoreCase(trimToEmpty(strategy)) && candidates.size() > 1) {
            return candidates.stream()
                    .min((left, right) -> Double.compare(
                            dailyUsage.getOrDefault(left.id(), 0.0d),
                            dailyUsage.getOrDefault(right.id(), 0.0d)
                    ))
                    .orElse(candidates.get(0));
        }
        long next = PROVIDER_SELECTION_COUNTER.getAndIncrement();
        int index = Math.floorMod(next, candidates.size());
        return candidates.get(index);
    }

    private boolean supportsVisibleMethod(String providerKey, String supportedTypes, String method) {
        String normalizedProviderKey = trimToEmpty(providerKey).toLowerCase();
        String normalizedMethod = trimToEmpty(method).toLowerCase();
        if ("stripe".equals(normalizedProviderKey)) {
            if ("stripe".equals(normalizedMethod)) {
                return true;
            }
            List<String> types = splitCsv(supportedTypes);
            return types.stream().map(this::normalizeVisibleMethod).anyMatch(normalizedMethod::equals);
        }
        if ("alipay".equals(normalizedProviderKey)) {
            if (!"alipay".equals(normalizedMethod)) {
                return false;
            }
            List<String> types = splitCsv(supportedTypes);
            return types.isEmpty() || types.stream().map(this::normalizeVisibleMethod).anyMatch("alipay"::equals);
        }
        if ("wxpay".equals(normalizedProviderKey)) {
            if (!"wxpay".equals(normalizedMethod)) {
                return false;
            }
            List<String> types = splitCsv(supportedTypes);
            return types.isEmpty() || types.stream().map(this::normalizeVisibleMethod).anyMatch("wxpay"::equals);
        }
        if ("easypay".equals(normalizedProviderKey)) {
            return splitCsv(supportedTypes).stream().map(this::normalizeVisibleMethod).anyMatch(normalizedMethod::equals);
        }
        return splitCsv(supportedTypes).stream().map(this::normalizeVisibleMethod).anyMatch(normalizedMethod::equals);
    }

    private String normalizeVisibleMethod(String paymentType) {
        String raw = trimToEmpty(paymentType).toLowerCase();
        if (raw.startsWith("wxpay")) {
            return "wxpay";
        }
        if (raw.startsWith("alipay")) {
            return "alipay";
        }
        if ("stripe".equals(raw) || "card".equals(raw) || "link".equals(raw)) {
            return "stripe";
        }
        return raw;
    }

    private String normalizeVisibleMethodSource(String method, String source) {
        String normalizedMethod = trimToEmpty(method).toLowerCase();
        String raw = trimToEmpty(source).toLowerCase();
        if ("alipay".equals(normalizedMethod)) {
            return switch (raw) {
                case "", "official_alipay", "alipay", "alipay_direct", "official" -> SOURCE_OFFICIAL_ALIPAY;
                case "easypay_alipay", "easypay" -> SOURCE_EASYPAY_ALIPAY;
                case "stripe_alipay", "stripe" -> SOURCE_STRIPE_ALIPAY;
                default -> "";
            };
        }
        if ("wxpay".equals(normalizedMethod)) {
            return switch (raw) {
                case "", "official_wxpay", "wxpay", "wxpay_direct", "wechat", "official" -> SOURCE_OFFICIAL_WXPAY;
                case "easypay_wxpay", "easypay" -> SOURCE_EASYPAY_WXPAY;
                case "stripe_wxpay", "stripe" -> SOURCE_STRIPE_WXPAY;
                default -> "";
            };
        }
        return raw;
    }

    private String providerKeyForSource(String source) {
        return switch (trimToEmpty(source).toLowerCase()) {
            case SOURCE_OFFICIAL_ALIPAY -> "alipay";
            case SOURCE_EASYPAY_ALIPAY, SOURCE_EASYPAY_WXPAY -> "easypay";
            case SOURCE_OFFICIAL_WXPAY -> "wxpay";
            case SOURCE_STRIPE_ALIPAY, SOURCE_STRIPE_WXPAY -> "stripe";
            default -> "";
        };
    }

    private String settingKeyForSource(String method) {
        return "alipay".equalsIgnoreCase(method)
                ? SETTING_PAYMENT_VISIBLE_METHOD_ALIPAY_SOURCE
                : SETTING_PAYMENT_VISIBLE_METHOD_WXPAY_SOURCE;
    }

    private String settingKeyForEnabled(String method) {
        return "alipay".equalsIgnoreCase(method)
                ? SETTING_PAYMENT_VISIBLE_METHOD_ALIPAY_ENABLED
                : SETTING_PAYMENT_VISIBLE_METHOD_WXPAY_ENABLED;
    }

    private Map<String, String> toStringMap(Map<String, Object> raw) {
        Map<String, String> result = new HashMap<>();
        raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private Map<String, Object> normalizeLimit(Map<String, Object> raw) {
        Map<String, Object> normalized = new HashMap<>();
        double dailyLimit = asDouble(firstValue(raw, "daily_limit", "dailyLimit"));
        double dailyUsed = asDouble(firstValue(raw, "daily_used", "dailyUsed"));
        normalized.put("daily_limit", dailyLimit);
        normalized.put("daily_used", dailyUsed);
        normalized.put("daily_remaining", Math.max(dailyLimit - dailyUsed, 0));
        normalized.put("single_min", asDouble(firstValue(raw, "single_min", "singleMin")));
        normalized.put("single_max", asDouble(firstValue(raw, "single_max", "singleMax")));
        normalized.put("fee_rate", asDouble(firstValue(raw, "fee_rate", "feeRate")));
        normalized.put("available", raw.isEmpty() || Boolean.parseBoolean(String.valueOf(raw.getOrDefault("available", true))));
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private boolean providerCanAcceptAmount(ProviderInstanceResponse provider, String paymentType, double orderAmount, double dailyUsed) {
        if (orderAmount <= 0) {
            return true;
        }
        Map<String, Object> limits = jsonHelper.readObjectMap(provider.limits());
        Object channel = limits.get(channelLimitKey(provider.provider_key(), paymentType));
        if (channel == null) {
            channel = limits.get(paymentType);
        }
        if (channel == null && "alipay".equals(paymentType)) {
            channel = limits.get("alipay_direct");
        }
        if (channel == null && "wxpay".equals(paymentType)) {
            channel = limits.get("wxpay_direct");
        }
        Map<String, Object> channelLimits = channel instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : limits;
        Map<String, Object> normalized = normalizeLimit(channelLimits);
        double singleMin = asDouble(normalized.get("single_min"));
        double singleMax = asDouble(normalized.get("single_max"));
        double dailyLimit = asDouble(normalized.get("daily_limit"));
        return (singleMin <= 0 || orderAmount >= singleMin)
                && (singleMax <= 0 || orderAmount <= singleMax)
                && (dailyLimit <= 0 || dailyUsed + orderAmount <= dailyLimit);
    }

    private String channelLimitKey(String providerKey, String paymentType) {
        return "stripe".equalsIgnoreCase(trimToEmpty(providerKey)) ? "stripe" : paymentType;
    }

    private Object firstValue(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            if (raw.containsKey(key)) {
                return raw.get(key);
            }
        }
        return 0;
    }

    private double mergeGlobalMin(double current, double candidate) {
        if (candidate <= 0) {
            return current;
        }
        if (current <= 0) {
            return candidate;
        }
        return Math.min(current, candidate);
    }

    private double asDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private double roundAmount(double value) {
        return Math.round(value * 100.0) / 100.0;
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

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
