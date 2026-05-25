package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StripePaymentClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_API_BASE = "https://api.stripe.com";
    private static final String CURRENCY_CNY = "cny";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public StripePaymentClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    StripePaymentClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public StripeCreateOrderResult createOrder(
            ProviderInstanceResponse provider,
            PaymentOrderResponse order,
            CreateOrderRequest request,
            String subject
    ) {
        Map<String, String> config = provider.config();
        String secretKey = requireConfig(config, "secretKey");
        List<String> methods = resolvePaymentMethodTypes(provider, order.payment_type());

        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", String.valueOf(yuanToFen(order.pay_amount())));
        form.put("currency", CURRENCY_CNY);
        form.put("description", subject);
        form.put("metadata[orderId]", order.out_trade_no());
        form.put("metadata[providerInstanceId]", String.valueOf(provider.id()));
        for (int i = 0; i < methods.size(); i++) {
            form.put("payment_method_types[" + i + "]", methods.get(i));
        }
        if (methods.contains("wechat_pay")) {
            form.put("payment_method_options[wechat_pay][client]", Boolean.TRUE.equals(request.is_mobile()) ? "mobile_web" : "web");
        }

        JsonNode payload = callStripe(
                config,
                secretKey,
                "POST",
                "/v1/payment_intents",
                form,
                "pi-" + order.out_trade_no()
        );
        return new StripeCreateOrderResult(
                payload.path("id").asText(""),
                payload.path("client_secret").asText(""),
                payload.path("status").asText("")
        );
    }

    public StripeQueryOrderResult queryOrder(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        Map<String, String> config = provider.config();
        String secretKey = requireConfig(config, "secretKey");
        String paymentIntentId = paymentIntentId(order);
        if (paymentIntentId.isBlank()) {
            throw new StructuredApiErrorException(400, "PAYMENT_TRADE_NO_MISSING", "stripe payment_trade_no is required");
        }

        JsonNode payload = callStripe(
                config,
                secretKey,
                "GET",
                "/v1/payment_intents/" + urlPath(paymentIntentId),
                Map.of(),
                null
        );
        return mapPaymentIntent(payload);
    }

    public void cancelPayment(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        Map<String, String> config = provider.config();
        String secretKey = requireConfig(config, "secretKey");
        String paymentIntentId = paymentIntentId(order);
        if (paymentIntentId.isBlank()) {
            return;
        }
        callStripe(
                config,
                secretKey,
                "POST",
                "/v1/payment_intents/" + urlPath(paymentIntentId) + "/cancel",
                Map.of(),
                "cancel-" + order.out_trade_no()
        );
    }

    public StripeRefundResult refund(ProviderInstanceResponse provider, PaymentOrderResponse order, double amount, String reason) {
        Map<String, String> config = provider.config();
        String secretKey = requireConfig(config, "secretKey");
        String paymentIntentId = paymentIntentId(order);
        if (paymentIntentId.isBlank()) {
            throw new StructuredApiErrorException(400, "PAYMENT_TRADE_NO_MISSING", "stripe payment_trade_no is required");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", paymentIntentId);
        form.put("amount", String.valueOf(yuanToFen(amount)));
        form.put("reason", "requested_by_customer");
        if (reason != null && !reason.isBlank()) {
            form.put("metadata[reason]", reason.trim());
        }
        JsonNode payload = callStripe(
                config,
                secretKey,
                "POST",
                "/v1/refunds",
                form,
                "refund-" + order.out_trade_no() + "-" + yuanToFen(amount)
        );
        return new StripeRefundResult(
                payload.path("id").asText(""),
                normalizeRefundStatus(payload.path("status").asText(""))
        );
    }

    List<String> resolvePaymentMethodTypes(ProviderInstanceResponse provider, String paymentType) {
        String normalizedType = PaymentResumeTokenService.normalizeVisibleMethod(paymentType);
        if ("alipay".equals(normalizedType)) {
            return List.of("alipay");
        }
        if ("wxpay".equals(normalizedType)) {
            return List.of("wechat_pay");
        }

        LinkedHashSet<String> methods = new LinkedHashSet<>();
        for (String type : provider.supported_types() == null ? List.<String>of() : provider.supported_types()) {
            switch (trimToEmpty(type).toLowerCase(Locale.ROOT)) {
                case "card" -> methods.add("card");
                case "alipay" -> methods.add("alipay");
                case "wxpay", "wechat_pay" -> methods.add("wechat_pay");
                case "link" -> methods.add("link");
                default -> {
                }
            }
        }
        if (methods.isEmpty()) {
            methods.add("card");
        }
        return new ArrayList<>(methods);
    }

    private JsonNode callStripe(
            Map<String, String> config,
            String secretKey,
            String method,
            String path,
            Map<String, String> form,
            String idempotencyKey
    ) {
        String apiBase = normalizeApiBase(config.get("apiBase"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + secretKey)
                .header("Stripe-Version", firstNonBlank(config.get("apiVersion"), "2024-06-20"));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = objectMapper.readTree(defaultString(response.body()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(
                        502,
                        "PAYMENT_GATEWAY_ERROR",
                        "stripe error: " + stripeErrorMessage(payload)
                );
            }
            return payload;
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "stripe request failed");
        }
    }

    private StripeQueryOrderResult mapPaymentIntent(JsonNode payload) {
        String status = payload.path("status").asText("");
        return new StripeQueryOrderResult(
                payload.path("id").asText(""),
                normalizePaymentIntentStatus(status),
                fenToYuan(payload.path("amount").asLong(0)),
                payload.path("created").asLong(0)
        );
    }

    private String normalizePaymentIntentStatus(String status) {
        return switch (trimToEmpty(status).toLowerCase(Locale.ROOT)) {
            case "succeeded" -> "paid";
            case "canceled", "requires_payment_method" -> "failed";
            default -> "pending";
        };
    }

    private String normalizeRefundStatus(String status) {
        return "succeeded".equalsIgnoreCase(trimToEmpty(status)) ? "success" : "pending";
    }

    private String stripeErrorMessage(JsonNode payload) {
        String message = payload.path("error").path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        return payload.path("error").path("code").asText("unknown");
    }

    private String requireConfig(Map<String, String> config, String key) {
        String value = trimToEmpty(config.get(key));
        if (value.isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "stripe config missing required key: " + key);
        }
        return value;
    }

    private String paymentIntentId(PaymentOrderResponse order) {
        return trimToEmpty(order.payment_trade_no());
    }

    private long yuanToFen(double amount) {
        return Math.round(amount * 100.0d);
    }

    private double fenToYuan(long fen) {
        return fen / 100.0d;
    }

    private String normalizeApiBase(String value) {
        String raw = trimToEmpty(value);
        if (raw.isBlank()) {
            return DEFAULT_API_BASE;
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private String encodeForm(Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(urlEncode(entry.getKey()) + "=" + urlEncode(defaultString(entry.getValue())));
        }
        return String.join("&", parts);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String urlPath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        return defaultString(second).trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record StripeCreateOrderResult(
            String tradeNo,
            String clientSecret,
            String status
    ) {
    }

    public record StripeQueryOrderResult(
            String tradeNo,
            String status,
            double amount,
            long created
    ) {
        public boolean paid() {
            return "paid".equalsIgnoreCase(status);
        }
    }

    public record StripeRefundResult(
            String refundId,
            String status
    ) {
    }
}
