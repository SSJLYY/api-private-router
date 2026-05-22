package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EasyPayPaymentClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ERROR_SUMMARY = 512;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EasyPayPaymentClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    EasyPayPaymentClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public EasyPayCreateOrderResult createOrder(
            ProviderInstanceResponse provider,
            PaymentOrderResponse order,
            CreateOrderRequest request,
            String notifyUrl,
            String returnUrl,
            String clientIp,
            String subject
    ) {
        Map<String, String> config = provider.config();
        String paymentMode = normalizePaymentMode(provider.payment_mode(), config.get("paymentMode"));
        String paymentType = PaymentResumeTokenService.normalizeVisibleMethod(order.payment_type());
        String apiBase = normalizeApiBase(config.get("apiBase"));
        String pid = requireConfig(config, "pid");
        String pkey = requireConfig(config, "pkey");
        notifyUrl = firstNonBlank(notifyUrl, config.get("notifyUrl"));
        returnUrl = firstNonBlank(returnUrl, config.get("returnUrl"));
        requireNonBlank(notifyUrl, "notifyUrl");
        requireNonBlank(returnUrl, "returnUrl");

        Map<String, String> params = new HashMap<>();
        params.put("pid", pid);
        params.put("type", paymentType);
        params.put("out_trade_no", order.out_trade_no());
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("name", subject);
        params.put("money", formatAmount(order.pay_amount()));
        if (order.provider_key() != null && order.provider_key().equalsIgnoreCase("easypay")) {
            String cid = resolveCid(config, paymentType);
            if (!cid.isBlank()) {
                params.put("cid", cid);
            }
        }
        if (Boolean.TRUE.equals(request.is_mobile())) {
            params.put("device", "mobile");
        }
        params.put("clientip", firstNonBlank(clientIp, "127.0.0.1"));
        params.put("sign", sign(params, pkey));
        params.put("sign_type", "MD5");

        if ("popup".equals(paymentMode)) {
            String payUrl = apiBase + "/submit.php?" + encodeForm(params);
            return new EasyPayCreateOrderResult("", payUrl, null, paymentMode);
        }

        EasyPayApiResponse response = callCreateApi(apiBase, params);
        String payUrl = response.payurl();
        if (Boolean.TRUE.equals(request.is_mobile()) && response.payurl2() != null && !response.payurl2().isBlank()) {
            payUrl = response.payurl2();
        }
        return new EasyPayCreateOrderResult(
                defaultString(response.tradeNo()),
                defaultString(payUrl),
                blankToNull(response.qrcode()),
                paymentMode
        );
    }

    public EasyPayQueryOrderResult queryOrder(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        Map<String, String> config = provider.config();
        String apiBase = normalizeApiBase(config.get("apiBase"));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("act", "order");
        params.put("pid", requireConfig(config, "pid"));
        params.put("key", requireConfig(config, "pkey"));
        params.put("out_trade_no", order.out_trade_no());

        EasyPayQueryApiResponse response = callApi(
                apiBase + "/api.php",
                params,
                EasyPayQueryApiResponse.class,
                "easypay query order"
        );
        return new EasyPayQueryOrderResult(
                order.out_trade_no(),
                response.status() == 1 ? "paid" : "pending",
                parseAmount(response.money())
        );
    }

    public EasyPayRefundResult refund(ProviderInstanceResponse provider, PaymentOrderResponse order, double amount, String reason) {
        Map<String, String> config = provider.config();
        String apiBase = normalizeApiBase(config.get("apiBase"));
        List<EasyPayRefundAttempt> attempts = refundAttempts(config, order, amount);
        if (attempts.isEmpty()) {
            throw new StructuredApiErrorException(400, "PAYMENT_TRADE_NO_MISSING", "easypay refund requires an order identifier");
        }

        StructuredApiErrorException firstError = null;
        for (int i = 0; i < attempts.size(); i++) {
            EasyPayRefundAttempt attempt = attempts.get(i);
            try {
                callRefundApi(apiBase + "/api.php?act=refund", attempt.params());
                return new EasyPayRefundResult(attempt.refundId(), "success");
            } catch (StructuredApiErrorException ex) {
                if (firstError == null) {
                    firstError = ex;
                }
                if (i + 1 < attempts.size() && isRefundOrderNotFound(ex.getMessage())) {
                    continue;
                }
                throw ex;
            }
        }
        throw firstError == null
                ? new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund failed")
                : firstError;
    }

    String normalizeApiBase(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(raw);
            if (uri.getScheme() != null && uri.getHost() != null) {
                String path = trimEndpointPath(uri.getPath());
                URI normalized = new URI(uri.getScheme(), uri.getAuthority(), path, null, null);
                return trimTrailingSlash(normalized.toString());
            }
        } catch (URISyntaxException ignored) {
            // Fall through to string normalization for legacy non-URI config values.
        }
        raw = raw.split("\\?", 2)[0].split("#", 2)[0];
        raw = trimEndpointPath(raw);
        return trimTrailingSlash(raw);
    }

    private String trimEndpointPath(String raw) {
        String path = trimTrailingSlash(raw == null ? "" : raw.trim());
        String lower = path.toLowerCase(Locale.ROOT);
        for (String endpoint : List.of("/submit.php", "/mapi.php", "/api.php")) {
            if (lower.endsWith(endpoint)) {
                return path.substring(0, path.length() - endpoint.length());
            }
        }
        return path;
    }

    private String trimTrailingSlash(String raw) {
        String result = raw == null ? "" : raw.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    String normalizePaymentMode(String paymentMode, String configPaymentMode) {
        String candidate = paymentMode == null || paymentMode.isBlank() ? configPaymentMode : paymentMode;
        String normalized = candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT);
        return "popup".equals(normalized) ? "popup" : "qrcode";
    }

    String resolveCid(Map<String, String> config, String paymentType) {
        String normalized = PaymentResumeTokenService.normalizeVisibleMethod(paymentType);
        if ("alipay".equals(normalized)) {
            String cidAlipay = blankToNull(config.get("cidAlipay"));
            return cidAlipay == null ? defaultString(config.get("cid")) : cidAlipay;
        }
        if ("wxpay".equals(normalized)) {
            String cidWxpay = blankToNull(config.get("cidWxpay"));
            return cidWxpay == null ? defaultString(config.get("cid")) : cidWxpay;
        }
        return defaultString(config.get("cid"));
    }

    String sign(Map<String, String> params, String pkey) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = defaultString(entry.getValue());
            if ("sign".equalsIgnoreCase(key) || "sign_type".equalsIgnoreCase(key) || value.isBlank()) {
                continue;
            }
            keys.add(key);
        }
        keys.sort(Comparator.naturalOrder());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                builder.append('&');
            }
            String key = keys.get(i);
            builder.append(key).append('=').append(defaultString(params.get(key)));
        }
        builder.append(pkey);
        return md5Hex(builder.toString());
    }

    String encodeForm(Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(urlEncode(entry.getKey()) + "=" + urlEncode(defaultString(entry.getValue())));
        }
        return String.join("&", parts);
    }

    private EasyPayApiResponse callCreateApi(String apiBase, Map<String, String> params) {
        return callApi(apiBase + "/mapi.php", params, EasyPayApiResponse.class, "easypay create order");
    }

    private <T> T callApi(String endpoint, Map<String, String> params, Class<T> responseType, String action) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(params), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(
                        502,
                        "PAYMENT_GATEWAY_ERROR",
                        action + " failed with HTTP " + response.statusCode()
                );
            }
            T payload = objectMapper.readValue(defaultString(response.body()), responseType);
            int code = payload instanceof EasyPayApiResponse createResponse
                    ? createResponse.code()
                    : payload instanceof EasyPayQueryApiResponse queryResponse
                    ? queryResponse.code()
                    : 1;
            String message = payload instanceof EasyPayApiResponse createResponse
                    ? createResponse.msg()
                    : payload instanceof EasyPayQueryApiResponse queryResponse
                    ? queryResponse.msg()
                    : "";
            if (code != 1) {
                throw new StructuredApiErrorException(
                        502,
                        "PAYMENT_GATEWAY_ERROR",
                        "easypay error: " + defaultString(message)
                );
            }
            return payload;
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", action + " failed");
        }
    }

    private void callRefundApi(String endpoint, Map<String, String> params) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(params), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            validateRefundResponse(response.statusCode(), response.body());
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund request failed");
        }
    }

    private void validateRefundResponse(int statusCode, String rawBody) {
        String body = defaultString(rawBody).trim();
        String summary = summarizeResponse(rawBody);
        if (statusCode < 200 || statusCode >= 300) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund HTTP " + statusCode + ": " + summary);
        }
        if (body.isBlank()) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund empty response (HTTP " + statusCode + "): " + summary);
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || (lower.startsWith("<") && lower.contains("html"))) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund non-JSON response (HTTP " + statusCode + "): " + summary);
        }
        try {
            EasyPayRefundApiResponse payload = objectMapper.readValue(body, EasyPayRefundApiResponse.class);
            if (!responseCodeIsSuccess(payload.code())) {
                String msg = firstNonBlank(payload.msg(), summary);
                throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund failed (HTTP " + statusCode + "): " + msg);
            }
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "easypay refund non-JSON response (HTTP " + statusCode + "): " + summary);
        }
    }

    private List<EasyPayRefundAttempt> refundAttempts(Map<String, String> config, PaymentOrderResponse order, double amount) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("pid", requireConfig(config, "pid"));
        base.put("key", requireConfig(config, "pkey"));
        base.put("money", formatAmount(amount));

        List<EasyPayRefundAttempt> attempts = new ArrayList<>();
        String outTradeNo = blankToNull(order.out_trade_no());
        if (outTradeNo != null) {
            Map<String, String> params = new LinkedHashMap<>(base);
            params.put("out_trade_no", outTradeNo);
            attempts.add(new EasyPayRefundAttempt(params, outTradeNo));
        }
        String tradeNo = blankToNull(order.payment_trade_no());
        if (tradeNo != null) {
            Map<String, String> params = new LinkedHashMap<>(base);
            params.put("trade_no", tradeNo);
            attempts.add(new EasyPayRefundAttempt(params, tradeNo));
        }
        return attempts;
    }

    private boolean responseCodeIsSuccess(Object code) {
        if (code instanceof Number number) {
            return number.intValue() == 1;
        }
        if (code instanceof String raw) {
            try {
                return Integer.parseInt(raw.trim()) == 1;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isRefundOrderNotFound(String message) {
        String lower = defaultString(message).toLowerCase(Locale.ROOT);
        return lower.contains("\u8ba2\u5355\u7f16\u53f7\u4e0d\u5b58\u5728")
                || lower.contains("\u8ba2\u5355\u4e0d\u5b58\u5728")
                || lower.contains("order not found")
                || lower.contains("not exist");
    }

    private String summarizeResponse(String rawBody) {
        String summary = String.join(" ", defaultString(rawBody).trim().split("\\s+"));
        if (summary.isBlank()) {
            return "<empty>";
        }
        return summary.length() > MAX_ERROR_SUMMARY ? summary.substring(0, MAX_ERROR_SUMMARY) + "..." : summary;
    }

    private double parseAmount(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0.0 : Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String requireConfig(Map<String, String> config, String key) {
        String value = blankToNull(config.get(key));
        if (value == null) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "easypay config missing required key: " + key);
        }
        return value;
    }

    private void requireNonBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "easypay config missing required key: " + key);
        }
    }

    private String formatAmount(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("md5 unavailable", ex);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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

    private String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    public record EasyPayCreateOrderResult(
            String tradeNo,
            String payUrl,
            String qrCode,
            String paymentMode
    ) {
    }

    record EasyPayApiResponse(
            int code,
            String msg,
            @com.fasterxml.jackson.annotation.JsonProperty("trade_no") String tradeNo,
            String payurl,
            String payurl2,
            String qrcode
    ) {
    }

    record EasyPayQueryApiResponse(
            int code,
            String msg,
            int status,
            String money
    ) {
    }

    record EasyPayRefundApiResponse(
            Object code,
            String msg
    ) {
    }

    private record EasyPayRefundAttempt(
            Map<String, String> params,
            String refundId
    ) {
    }

    public record EasyPayQueryOrderResult(
            String tradeNo,
            String status,
            double amount
    ) {
        public boolean paid() {
            return "paid".equalsIgnoreCase(status);
        }
    }

    public record EasyPayRefundResult(
            String refundId,
            String status
    ) {
    }
}
