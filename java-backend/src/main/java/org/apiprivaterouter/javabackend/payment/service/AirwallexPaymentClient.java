package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AirwallexPaymentClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final String DEMO_API_BASE = "https://api-demo.airwallex.com/api/v1";
    private static final String PROD_API_BASE = "https://api.airwallex.com/api/v1";
    private static final String DEFAULT_CURRENCY = "CNY";
    private static final String DEFAULT_COUNTRY_CODE = "CN";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @Autowired
    public AirwallexPaymentClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    AirwallexPaymentClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public AirwallexCreateOrderResult createOrder(
            ProviderInstanceResponse provider,
            PaymentOrderResponse order,
            CreateOrderRequest request,
            String notifyUrl,
            String returnUrl,
            String subject
    ) {
        Map<String, String> config = provider.config();
        String accessToken = getAccessToken(config);
        String apiBase = normalizeApiBase(config.get("apiBase"));
        String currency = resolveCurrency(config);
        String accountId = trimToEmpty(config.get("accountId"));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("merchant_order_id", order.out_trade_no());
        body.put("amount", order.pay_amount());
        body.put("currency", currency);
        body.put("merchant_return_url", returnUrl == null ? "" : returnUrl);
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("notify_url", notifyUrl);
            body.set("metadata", metadata);
        }
        if (subject != null && !subject.isBlank()) {
            body.put("descriptor", subject);
        }

        JsonNode response = callApi(apiBase, "/pa/payment_intents/create", "POST", body.toString(), accessToken, accountId);
        String intentId = response.path("id").asText("");
        String clientSecret = response.path("client_secret").asText("");
        String status = response.path("status").asText("");

        return new AirwallexCreateOrderResult(intentId, clientSecret, status);
    }

    public AirwallexQueryOrderResult queryOrder(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        Map<String, String> config = provider.config();
        String accessToken = getAccessToken(config);
        String apiBase = normalizeApiBase(config.get("apiBase"));
        String accountId = trimToEmpty(config.get("accountId"));
        String paymentIntentId = trimToEmpty(order.payment_trade_no());
        if (paymentIntentId.isBlank()) {
            throw new StructuredApiErrorException(400, "PAYMENT_TRADE_NO_MISSING", "airwallex payment_trade_no is required");
        }

        JsonNode response = callApi(apiBase, "/pa/payment_intents/" + paymentIntentId, "GET", null, accessToken, accountId);
        String status = normalizePaymentIntentStatus(response.path("status").asText(""));
        double amount = response.path("amount").asDouble(0);
        long created = response.path("created_at").asLong(0);

        return new AirwallexQueryOrderResult(paymentIntentId, status, amount, created);
    }

    public void cancelPayment(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        Map<String, String> config = provider.config();
        String accessToken = getAccessToken(config);
        String apiBase = normalizeApiBase(config.get("apiBase"));
        String accountId = trimToEmpty(config.get("accountId"));
        String paymentIntentId = trimToEmpty(order.payment_trade_no());
        if (paymentIntentId.isBlank()) {
            return;
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "CANCELLED");
        callApi(apiBase, "/pa/payment_intents/" + paymentIntentId + "/cancel", "POST", body.toString(), accessToken, accountId);
    }

    public AirwallexRefundResult refund(ProviderInstanceResponse provider, PaymentOrderResponse order, double amount, String reason) {
        Map<String, String> config = provider.config();
        String accessToken = getAccessToken(config);
        String apiBase = normalizeApiBase(config.get("apiBase"));
        String accountId = trimToEmpty(config.get("accountId"));
        String paymentIntentId = trimToEmpty(order.payment_trade_no());
        if (paymentIntentId.isBlank()) {
            throw new StructuredApiErrorException(400, "PAYMENT_TRADE_NO_MISSING", "airwallex payment_trade_no is required");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("payment_intent_id", paymentIntentId);
        body.put("amount", amount);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason.trim());
        }

        JsonNode response = callApi(apiBase, "/pa/refunds/create", "POST", body.toString(), accessToken, accountId);
        String refundId = response.path("id").asText("");
        String status = normalizeRefundStatus(response.path("status").asText(""));

        return new AirwallexRefundResult(refundId, status);
    }

    private String getAccessToken(Map<String, String> config) {
        String clientId = requireConfig(config, "clientId");
        String apiKey = requireConfig(config, "apiKey");
        String cacheKey = clientId + ":" + apiKey;
        String accountId = trimToEmpty(config.get("accountId"));

        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt > Instant.now().getEpochSecond()) {
            return cached.token;
        }

        String apiBase = normalizeApiBase(config.get("apiBase"));
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + "/authentication/login"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey);
            if (!accountId.isBlank()) {
                builder.header("x-login-as", accountId);
            }
            builder.POST(HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorMsg = "airwallex auth failed";
                try {
                    JsonNode errorNode = objectMapper.readTree(defaultString(response.body()));
                    String msg = errorNode.path("message").asText("");
                    String code = errorNode.path("code").asText("");
                    if (!msg.isBlank()) {
                        errorMsg = msg;
                    } else if (!code.isBlank()) {
                        errorMsg = code;
                    }
                } catch (Exception ignored) {
                }
                throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "airwallex auth error: " + errorMsg + " — check clientId/apiKey credentials");
            }
            JsonNode payload = objectMapper.readTree(defaultString(response.body()));
            String token = payload.path("token").asText("");
            if (token.isBlank()) {
                throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "airwallex auth returned empty token");
            }
            long expiresAt = Instant.now().getEpochSecond() + 118;
            tokenCache.put(cacheKey, new CachedToken(token, expiresAt));
            return token;
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "airwallex auth request failed");
        }
    }

    private JsonNode callApi(String apiBase, String path, String method, String body, String accessToken, String accountId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json");
            if (!accountId.isBlank()) {
                builder.header("x-on-behalf-of", accountId);
            }
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = objectMapper.readTree(defaultString(response.body()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(
                        502,
                        "PAYMENT_GATEWAY_ERROR",
                        "airwallex error: " + airwallexErrorMessage(payload)
                );
            }
            return payload;
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "airwallex request failed");
        }
    }

    private String normalizePaymentIntentStatus(String status) {
        return switch (trimToEmpty(status).toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED" -> "paid";
            case "CANCELLED", "FAILED" -> "failed";
            default -> "pending";
        };
    }

    private String normalizeRefundStatus(String status) {
        return switch (trimToEmpty(status).toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED" -> "success";
            default -> "pending";
        };
    }

    private String airwallexErrorMessage(JsonNode payload) {
        String message = payload.path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        return payload.path("code").asText("unknown");
    }

    private String normalizeApiBase(String value) {
        String raw = trimToEmpty(value);
        if (raw.isBlank()) {
            return PROD_API_BASE;
        }
        if (raw.contains("demo") || raw.contains("sandbox")) {
            if (!raw.endsWith("/api/v1")) {
                raw = raw.replaceAll("/+$", "") + "/api/v1";
            }
            return raw;
        }
        if (!raw.endsWith("/api/v1")) {
            raw = raw.replaceAll("/+$", "") + "/api/v1";
        }
        return raw;
    }

    private String resolveCurrency(Map<String, String> config) {
        String currency = trimToEmpty(config.get("currency"));
        return currency.isBlank() ? DEFAULT_CURRENCY : currency.toUpperCase(Locale.ROOT);
    }

    private String requireConfig(Map<String, String> config, String key) {
        String value = trimToEmpty(config.get(key));
        if (value.isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "airwallex config missing required key: " + key);
        }
        return value;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    public record AirwallexCreateOrderResult(String tradeNo, String clientSecret, String status) {
    }

    public record AirwallexQueryOrderResult(String tradeNo, String status, double amount, long created) {
        public boolean paid() {
            return "paid".equalsIgnoreCase(status);
        }
    }

    public record AirwallexRefundResult(String refundId, String status) {
    }

    private record CachedToken(String token, long expiresAt) {
    }
}
