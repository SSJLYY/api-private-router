package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.WechatJsapiPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WxpayPaymentClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_API_BASE = "https://api.mch.weixin.qq.com";
    private static final String CURRENCY_CNY = "CNY";
    private static final String AUTH_SCHEMA = "WECHATPAY2-SHA256-RSA2048";
    private static final String MODE_NATIVE = "native";
    private static final String MODE_H5 = "h5";
    private static final String MODE_JSAPI = "jsapi";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public WxpayPaymentClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    WxpayPaymentClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public WxpayCreateOrderResult createOrder(
            ProviderInstanceResponse provider,
            PaymentOrderResponse order,
            CreateOrderRequest request,
            String notifyUrl,
            String returnUrl,
            String clientIp,
            String subject
    ) {
        WxpayConfig config = loadConfig(provider);
        String mode = resolveCreateMode(request);
        int totalFen = yuanToFen(order.pay_amount());

        return switch (mode) {
            case MODE_JSAPI -> createJsapiOrder(config, order, notifyUrl, totalFen, subject, trimToEmpty(request.openid()), clientIp);
            case MODE_H5 -> createH5Order(config, order, notifyUrl, returnUrl, totalFen, subject, clientIp);
            default -> createNativeOrder(config, order, notifyUrl, totalFen, subject);
        };
    }

    public WxpayQueryOrderResult queryOrder(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        WxpayConfig config = loadConfig(provider);
        JsonNode payload = callWxpay(
                config,
                "GET",
                "/v3/pay/transactions/out-trade-no/" + urlPath(order.out_trade_no()) + "?mchid=" + urlEncode(config.merchantId()),
                Map.of(),
                null
        );
        return mapQueryOrder(payload);
    }

    public void cancelPayment(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        WxpayConfig config = loadConfig(provider);
        Map<String, Object> payload = Map.of("mchid", config.merchantId());
        callWxpay(
                config,
                "POST",
                "/v3/pay/transactions/out-trade-no/" + urlPath(order.out_trade_no()) + "/close",
                payload,
                "cancel-" + order.out_trade_no()
        );
    }

    public WxpayRefundResult refund(ProviderInstanceResponse provider, PaymentOrderResponse order, double amount, String reason) {
        WxpayConfig config = loadConfig(provider);
        int refundFen = yuanToFen(amount);
        int totalFen = yuanToFen(order.pay_amount());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("out_trade_no", order.out_trade_no());
        payload.put("out_refund_no", order.out_trade_no() + "-refund-" + UUID.randomUUID().toString().replace("-", ""));
        payload.put("reason", trimToEmpty(reason));
        payload.put("amount", Map.of(
                "refund", refundFen,
                "total", totalFen,
                "currency", CURRENCY_CNY
        ));

        JsonNode response = callWxpay(
                config,
                "POST",
                "/v3/refund/domestic/refunds",
                payload,
                "refund-" + order.out_trade_no() + "-" + refundFen
        );
        String status = normalizeRefundStatus(response.path("status").asText(""));
        return new WxpayRefundResult(
                response.path("refund_id").asText(""),
                status
        );
    }

    private WxpayCreateOrderResult createJsapiOrder(
            WxpayConfig config,
            PaymentOrderResponse order,
            String notifyUrl,
            int totalFen,
            String subject,
            String openid,
            String clientIp
    ) {
        if (openid.isBlank()) {
            throw new StructuredApiErrorException(400, "WXPAY_OPENID_REQUIRED", "wxpay JSAPI payment requires openid");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appid", config.jsapiAppId());
        payload.put("mchid", config.merchantId());
        payload.put("description", subject);
        payload.put("out_trade_no", order.out_trade_no());
        payload.put("notify_url", notifyUrl);
        payload.put("amount", Map.of("total", totalFen, "currency", CURRENCY_CNY));
        payload.put("payer", Map.of("openid", openid));
        if (!trimToEmpty(clientIp).isBlank()) {
            payload.put("scene_info", Map.of("payer_client_ip", clientIp.trim()));
        }

        JsonNode response = callWxpay(
                config,
                "POST",
                "/v3/pay/transactions/jsapi",
                payload,
                "create-" + order.out_trade_no()
        );
        String prepayId = response.path("prepay_id").asText("");
        if (prepayId.isBlank()) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "wxpay JSAPI missing prepay_id");
        }
        String nonceStr = randomNonce();
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String pkg = "prepay_id=" + prepayId;
        String signType = "RSA";
        String paySign = signJsapi(config, config.jsapiAppId(), timeStamp, nonceStr, pkg);

        return new WxpayCreateOrderResult(
                "",
                null,
                order.out_trade_no(),
                "jsapi_ready",
                new WechatJsapiPayload(
                        config.jsapiAppId(),
                        timeStamp,
                        nonceStr,
                        pkg,
                        signType,
                        paySign
                ),
                "redirect"
        );
    }

    private WxpayCreateOrderResult createNativeOrder(
            WxpayConfig config,
            PaymentOrderResponse order,
            String notifyUrl,
            int totalFen,
            String subject
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appid", config.appId());
        payload.put("mchid", config.merchantId());
        payload.put("description", subject);
        payload.put("out_trade_no", order.out_trade_no());
        payload.put("notify_url", notifyUrl);
        payload.put("amount", Map.of("total", totalFen, "currency", CURRENCY_CNY));

        JsonNode response = callWxpay(
                config,
                "POST",
                "/v3/pay/transactions/native",
                payload,
                "create-" + order.out_trade_no()
        );
        String codeUrl = response.path("code_url").asText("");
        if (codeUrl.isBlank()) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "wxpay native missing code_url");
        }
        return new WxpayCreateOrderResult("", codeUrl, order.out_trade_no(), "order_created", null, "qrcode");
    }

    private WxpayCreateOrderResult createH5Order(
            WxpayConfig config,
            PaymentOrderResponse order,
            String notifyUrl,
            String returnUrl,
            int totalFen,
            String subject,
            String clientIp
    ) {
        String payerIp = trimToEmpty(clientIp);
        if (payerIp.isBlank()) {
            throw new StructuredApiErrorException(400, "WXPAY_CLIENT_IP_REQUIRED", "wxpay H5 payment requires client IP");
        }

        Map<String, Object> h5Info = new LinkedHashMap<>();
        h5Info.put("type", "Wap");
        if (!config.h5AppName().isBlank()) {
            h5Info.put("app_name", config.h5AppName());
        }
        if (!config.h5AppUrl().isBlank()) {
            h5Info.put("app_url", config.h5AppUrl());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appid", config.appId());
        payload.put("mchid", config.merchantId());
        payload.put("description", subject);
        payload.put("out_trade_no", order.out_trade_no());
        payload.put("notify_url", notifyUrl);
        payload.put("amount", Map.of("total", totalFen, "currency", CURRENCY_CNY));
        payload.put("scene_info", Map.of(
                "payer_client_ip", payerIp,
                "h5_info", h5Info
        ));

        JsonNode response = callWxpay(
                config,
                "POST",
                "/v3/pay/transactions/h5",
                payload,
                "create-" + order.out_trade_no()
        );
        String h5Url = response.path("h5_url").asText("");
        if (h5Url.isBlank()) {
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "wxpay H5 missing h5_url");
        }
        return new WxpayCreateOrderResult(
                appendRedirectUrl(h5Url, returnUrl, order.out_trade_no(), order.payment_type()),
                null,
                order.out_trade_no(),
                "order_created",
                null,
                "redirect"
        );
    }

    private WxpayQueryOrderResult mapQueryOrder(JsonNode payload) {
        String transactionId = payload.path("transaction_id").asText("");
        String tradeState = payload.path("trade_state").asText("");
        String successTime = payload.path("success_time").asText("");
        long totalFen = payload.path("amount").path("total").asLong(0);
        return new WxpayQueryOrderResult(
                transactionId,
                normalizeTradeState(tradeState),
                fenToYuan(totalFen),
                successTime
        );
    }

    private JsonNode callWxpay(
            WxpayConfig config,
            String method,
            String pathAndQuery,
            Map<String, Object> body,
            String idempotencyKey
    ) {
        String bodyJson = "GET".equalsIgnoreCase(method) ? "" : writeJson(body);
        String authorization = buildAuthorization(config, method, pathAndQuery, bodyJson);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.apiBase() + pathAndQuery))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .header("Wechatpay-Serial", config.certSerial());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method.toUpperCase(Locale.ROOT), HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = parseJson(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(
                        502,
                        "PAYMENT_GATEWAY_ERROR",
                        "wxpay error: " + wxpayErrorMessage(payload, response.body())
                );
            }
            return payload;
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", "wxpay request failed");
        }
    }

    private String buildAuthorization(WxpayConfig config, String method, String pathAndQuery, String bodyJson) {
        String nonceStr = randomNonce();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String message = method.toUpperCase(Locale.ROOT) + "\n"
                + pathAndQuery + "\n"
                + timestamp + "\n"
                + nonceStr + "\n"
                + bodyJson + "\n";
        String signature = sign(config.privateKey(), message);
        return AUTH_SCHEMA
                + " mchid=\"" + config.merchantId() + "\""
                + ",nonce_str=\"" + nonceStr + "\""
                + ",timestamp=\"" + timestamp + "\""
                + ",serial_no=\"" + config.certSerial() + "\""
                + ",signature=\"" + signature + "\"";
    }

    private String signJsapi(WxpayConfig config, String appId, String timeStamp, String nonceStr, String pkg) {
        String message = appId + "\n"
                + timeStamp + "\n"
                + nonceStr + "\n"
                + pkg + "\n";
        return sign(config.privateKey(), message);
    }

    private String sign(PrivateKey privateKey, String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "wxpay signature failed");
        }
    }

    private WxpayConfig loadConfig(ProviderInstanceResponse provider) {
        Map<String, String> config = provider.config();
        String appId = requireConfig(config, "appId");
        String merchantId = requireConfig(config, "mchId");
        String privateKeyPem = requireConfig(config, "privateKey");
        String apiV3Key = requireConfig(config, "apiV3Key");
        String certSerial = requireConfig(config, "certSerial");
        requireConfig(config, "publicKey");
        requireConfig(config, "publicKeyId");
        if (apiV3Key.trim().length() != 32) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "wxpay apiV3Key must be 32 characters");
        }
        String privateKeyText = normalizePem(privateKeyPem, "PRIVATE KEY");
        String jsapiAppId = firstNonBlank(config.get("mpAppId"), appId);
        return new WxpayConfig(
                normalizeApiBase(config.get("apiBase")),
                appId,
                merchantId,
                loadPrivateKey(privateKeyText),
                apiV3Key.trim(),
                certSerial.trim(),
                jsapiAppId,
                trimToEmpty(config.get("h5AppName")),
                trimToEmpty(config.get("h5AppUrl"))
        );
    }

    private PrivateKey loadPrivateKey(String pem) {
        try {
            String normalized = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception ex) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "wxpay privateKey is invalid");
        }
    }

    private String resolveCreateMode(CreateOrderRequest request) {
        if (!trimToEmpty(request.openid()).isBlank()) {
            return MODE_JSAPI;
        }
        if (Boolean.TRUE.equals(request.is_mobile())) {
            return MODE_H5;
        }
        return MODE_NATIVE;
    }

    private String appendRedirectUrl(String h5Url, String returnUrl, String outTradeNo, String paymentType) {
        if (trimToEmpty(h5Url).isBlank() || trimToEmpty(returnUrl).isBlank()) {
            return trimToEmpty(h5Url);
        }
        try {
            URI uri = new URI(returnUrl.trim());
            String queryPrefix = uri.getQuery() == null || uri.getQuery().isBlank() ? "" : uri.getQuery() + "&";
            String query = queryPrefix
                    + "out_trade_no=" + urlEncode(outTradeNo)
                    + "&payment_type=" + urlEncode(trimToEmpty(paymentType));
            URI redirectUri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), query, null);
            String separator = h5Url.contains("?") ? "&" : "?";
            return h5Url + separator + "redirect_url=" + urlEncode(redirectUri.toString());
        } catch (Exception ex) {
            throw new StructuredApiErrorException(400, "INVALID_RETURN_URL", "return_url must be a valid URL");
        }
    }

    private String normalizeTradeState(String tradeState) {
        return switch (trimToEmpty(tradeState).toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> "paid";
            case "REFUND" -> "refunded";
            case "CLOSED", "PAYERROR" -> "failed";
            default -> "pending";
        };
    }

    private String normalizeRefundStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(trimToEmpty(status)) ? "success" : "pending";
    }

    private String wxpayErrorMessage(JsonNode payload, String rawBody) {
        String message = payload.path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        String code = payload.path("code").asText("");
        if (!code.isBlank()) {
            return code;
        }
        return trimToEmpty(rawBody);
    }

    private JsonNode parseJson(String rawBody) throws IOException {
        String body = trimToEmpty(rawBody);
        if (body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to serialize wxpay payload", ex);
        }
    }

    private int yuanToFen(double amount) {
        return (int) Math.round(amount * 100.0d);
    }

    private double fenToYuan(long amount) {
        return amount / 100.0d;
    }

    private String randomNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String requireConfig(Map<String, String> config, String key) {
        String value = trimToEmpty(config.get(key));
        if (value.isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "wxpay config missing required key: " + key);
        }
        return value;
    }

    private String normalizePem(String value, String label) {
        String trimmed = trimToEmpty(value);
        if (trimmed.startsWith("-----BEGIN")) {
            return trimmed;
        }
        return "-----BEGIN " + label + "-----\n" + trimmed + "\n-----END " + label + "-----";
    }

    private String normalizeApiBase(String value) {
        String raw = trimToEmpty(value);
        if (raw.isBlank()) {
            return DEFAULT_API_BASE;
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        return trimToEmpty(second);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String urlPath(String value) {
        return urlEncode(value).replace("+", "%20");
    }

    record WxpayConfig(
            String apiBase,
            String appId,
            String merchantId,
            PrivateKey privateKey,
            String apiV3Key,
            String certSerial,
            String jsapiAppId,
            String h5AppName,
            String h5AppUrl
    ) {
    }

    public record WxpayCreateOrderResult(
            String payUrl,
            String qrCode,
            String paymentTradeNo,
            String resultType,
            WechatJsapiPayload jsapiPayload,
            String paymentMode
    ) {
    }

    public record WxpayQueryOrderResult(
            String tradeNo,
            String status,
            double amount,
            String paidAt
    ) {
        public boolean paid() {
            return "paid".equalsIgnoreCase(status);
        }
    }

    public record WxpayRefundResult(
            String refundId,
            String status
    ) {
    }
}
