package org.apiprivaterouter.javabackend.payment.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class PaymentRepositoryTest {

    @Test
    void loadLimitsSnapshotAggregatesVisibleMethodsAndRespectsConfiguredSources() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        JsonHelper jsonHelper = mock(JsonHelper.class);
        PaymentRepository repository = new PaymentRepository(jdbcTemplate, jsonHelper);

        List<Map<String, Object>> providerRows = List.of(
                Map.of(
                        "provider_key", "easypay",
                        "supported_types", "alipay,wxpay",
                        "limits", "easypay-limits"
                ),
                Map.of(
                        "provider_key", "wxpay",
                        "supported_types", "wxpay_direct",
                        "limits", "wxpay-limits"
                ),
                Map.of(
                        "provider_key", "stripe",
                        "supported_types", "card,link",
                        "limits", "stripe-limits"
                )
        );
        List<Map<String, Object>> settingRows = List.of(
                Map.of("key", "payment_visible_method_alipay_source", "value", "easypay_alipay"),
                Map.of("key", "payment_visible_method_wxpay_source", "value", "official_wxpay"),
                Map.of("key", "payment_visible_method_alipay_enabled", "value", "true"),
                Map.of("key", "payment_visible_method_wxpay_enabled", "value", "true")
        );

        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("from payment_provider_instances")),
                any(MapSqlParameterSource.class)
        )).thenReturn(providerRows);
        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("from settings")),
                any(MapSqlParameterSource.class)
        )).thenReturn(settingRows);

        when(jsonHelper.readObjectMap("easypay-limits")).thenReturn(Map.of(
                "single_min", 10.0,
                "single_max", 100.0,
                "daily_limit", 1000.0,
                "daily_used", 100.0,
                "fee_rate", 2.5,
                "available", true
        ));
        when(jsonHelper.readObjectMap("wxpay-limits")).thenReturn(Map.of(
                "single_min", 20.0,
                "single_max", 200.0,
                "daily_limit", 0.0,
                "daily_used", 0.0,
                "fee_rate", 1.0,
                "available", true
        ));
        when(jsonHelper.readObjectMap("stripe-limits")).thenReturn(Map.of(
                "single_min", 5.0,
                "single_max", 500.0,
                "daily_limit", 5000.0,
                "daily_used", 200.0,
                "fee_rate", 3.0,
                "available", true
        ));

        Map<String, Object> snapshot = repository.loadLimitsSnapshot();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> methods = (Map<String, Map<String, Object>>) snapshot.get("methods");
        assertTrue(methods.containsKey("alipay"));
        assertTrue(methods.containsKey("wxpay"));
        assertTrue(methods.containsKey("stripe"));
        assertFalse(methods.containsKey("easypay"));

        assertEquals(10.0, methods.get("alipay").get("single_min"));
        assertEquals(100.0, methods.get("alipay").get("single_max"));
        assertEquals(20.0, methods.get("wxpay").get("single_min"));
        assertEquals(200.0, methods.get("wxpay").get("single_max"));
        assertEquals(0.0, methods.get("wxpay").get("daily_limit"));
        assertEquals(5.0, snapshot.get("global_min"));
        assertEquals(500.0, snapshot.get("global_max"));
    }

    @Test
    void resolveCreateProviderUsesVisibleMethodSourceForAlipay() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        JsonHelper jsonHelper = mock(JsonHelper.class);
        PaymentRepository repository = new PaymentRepository(jdbcTemplate, jsonHelper);

        List<Map<String, Object>> settingRows = List.of(
                Map.of("key", "payment_visible_method_alipay_source", "value", "easypay_alipay")
        );
        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("from settings")),
                any(MapSqlParameterSource.class)
        )).thenReturn(settingRows);
        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("from payment_orders")),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of());

        PaymentRepository spy = spy(repository);
        doReturn(List.of(
                new ProviderInstanceResponse(1L, "alipay", "Official Alipay", Map.of(), List.of("alipay"), true, "redirect", false, false, "", 0),
                new ProviderInstanceResponse(2L, "easypay", "EasyPay", Map.of(), List.of("alipay", "wxpay"), true, "popup", false, false, "", 1)
        )).when(spy).loadProviderInstances();

        ProviderInstanceResponse selected = spy.resolveCreateProvider("alipay");

        assertEquals("easypay", selected.provider_key());
        assertEquals(2L, selected.id());
    }

    @Test
    void resolveCreateProviderFiltersDailyLimitAndUsesLeastAmountStrategy() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        JsonHelper jsonHelper = mock(JsonHelper.class);
        PaymentRepository repository = new PaymentRepository(jdbcTemplate, jsonHelper);

        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("from settings")),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(
                Map.of("key", "LOAD_BALANCE_STRATEGY", "value", "least-amount")
        ));
        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("from payment_orders")),
                any(MapSqlParameterSource.class)
        )).thenReturn(List.of(
                Map.of("provider_instance_id", "1", "daily_used", 90.0),
                Map.of("provider_instance_id", "2", "daily_used", 10.0),
                Map.of("provider_instance_id", "3", "daily_used", 20.0)
        ));
        when(jsonHelper.readObjectMap("limit-1")).thenReturn(Map.of(
                "stripe", Map.of("singleMin", 1.0, "singleMax", 100.0, "dailyLimit", 100.0)
        ));
        when(jsonHelper.readObjectMap("limit-2")).thenReturn(Map.of(
                "stripe", Map.of("singleMin", 1.0, "singleMax", 100.0, "dailyLimit", 100.0)
        ));
        when(jsonHelper.readObjectMap("limit-3")).thenReturn(Map.of(
                "stripe", Map.of("singleMin", 1.0, "singleMax", 100.0, "dailyLimit", 100.0)
        ));

        PaymentRepository spy = spy(repository);
        doReturn(List.of(
                new ProviderInstanceResponse(1L, "stripe", "Stripe 1", Map.of(), List.of("card"), true, "redirect", false, false, "limit-1", 0),
                new ProviderInstanceResponse(2L, "stripe", "Stripe 2", Map.of(), List.of("card"), true, "redirect", false, false, "limit-2", 1),
                new ProviderInstanceResponse(3L, "stripe", "Stripe 3", Map.of(), List.of("card"), true, "redirect", false, false, "limit-3", 2)
        )).when(spy).loadProviderInstances();

        ProviderInstanceResponse selected = spy.resolveCreateProvider("stripe", 20.0);

        assertEquals(2L, selected.id());
    }
}
