package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookCandidate;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookNotification;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PaymentWebhookProviderVerifier {

    private final ObjectMapper objectMapper;

    public PaymentWebhookProviderVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaymentWebhookNotification verify(
            PaymentWebhookCandidate candidate,
            String providerKey,
            String rawBody,
            Map<String, String> headers
    ) {
        return switch (normalize(providerKey)) {
            case "easypay" -> verifyEasyPay(candidate, rawBody);
            case "alipay" -> verifyAlipay(candidate, rawBody);
            case "stripe" -> verifyStripe(candidate, rawBody, headers);
            case "wxpay" -> verifyWxpay(candidate, rawBody, headers);
            default -> throw new IllegalArgumentException("unsupported webhook provider: " + providerKey);
        };
    }

    private PaymentWebhookNotification verifyEasyPay(PaymentWebhookCandidate candidate, String rawBody) {
        Map<String, String> params = parseForm(rawBody);
        String sign = trimToEmpty(params.get("sign"));
        String pkey = trimToEmpty(candidate.config().get("pkey"));
        if (sign.isBlank() || pkey.isBlank()) {
            throw new IllegalArgumentException("invalid easypay signature config");
        }
        String expected = md5Hex(buildEasyPaySignBase(params) + pkey);
        if (!expected.equalsIgnoreCase(sign)) {
            throw new IllegalArgumentException("invalid easypay signature");
        }
        String status = "TRADE_SUCCESS".equalsIgnoreCase(trimToEmpty(params.get("trade_status"))) ? "success" : "failed";
        Map<String, String> metadata = new HashMap<>();
        if (!trimToEmpty(params.get("pid")).isBlank()) {
            metadata.put("pid", trimToEmpty(params.get("pid")));
        }
        return new PaymentWebhookNotification(
                "easypay",
                trimToEmpty(params.get("trade_no")),
                trimToEmpty(params.get("out_trade_no")),
                parseAmount(params.get("money")),
                status,
                rawBody == null ? "" : rawBody,
                metadata
        );
    }

    private PaymentWebhookNotification verifyAlipay(PaymentWebhookCandidate candidate, String rawBody) {
        Map<String, String> params = parseForm(rawBody);
        String sign = trimToEmpty(params.remove("sign"));
        params.remove("sign_type");
        if (sign.isBlank()) {
            throw new IllegalArgumentException("missing alipay sign");
        }
        String publicKeyPem = firstNonBlank(candidate.config().get("publicKey"), candidate.config().get("alipayPublicKey"));
        if (publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("missing alipay public key");
        }
        String content = buildAlipaySignContent(params);
        if (!verifyRsa(content.getBytes(StandardCharsets.UTF_8), sign, publicKeyPem, "SHA256withRSA")) {
            throw new IllegalArgumentException("invalid alipay signature");
        }
        String tradeStatus = trimToEmpty(params.get("trade_status"));
        String status = ("TRADE_SUCCESS".equalsIgnoreCase(tradeStatus) || "TRADE_FINISHED".equalsIgnoreCase(tradeStatus))
                ? "success"
                : "failed";
        Map<String, String> metadata = new HashMap<>();
        if (!trimToEmpty(params.get("app_id")).isBlank()) {
            metadata.put("app_id", trimToEmpty(params.get("app_id")));
        }
        double amount = firstPositiveAmount(
                params.get("total_amount"),
                params.get("receipt_amount"),
                params.get("buyer_pay_amount")
        );
        return new PaymentWebhookNotification(
                "alipay",
                trimToEmpty(params.get("trade_no")),
                trimToEmpty(params.get("out_trade_no")),
                amount,
                status,
                rawBody == null ? "" : rawBody,
                metadata
        );
    }

    private PaymentWebhookNotification verifyStripe(PaymentWebhookCandidate candidate, String rawBody, Map<String, String> headers) {
        String secret = trimToEmpty(candidate.config().get("webhookSecret"));
        String signature = trimToEmpty(headers.get("stripe-signature"));
        if (secret.isBlank() || signature.isBlank()) {
            throw new IllegalArgumentException("missing stripe signature");
        }
        StripeSignature stripeSignature = parseStripeSignature(signature);
        String signedPayload = stripeSignature.timestamp() + "." + (rawBody == null ? "" : rawBody);
        String expected = hmacSha256Hex(secret, signedPayload);
        if (stripeSignature.v1().isEmpty() || stripeSignature.v1().stream().noneMatch(value -> MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8)
        ))) {
            throw new IllegalArgumentException("invalid stripe signature");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody == null ? "" : rawBody);
            String type = root.path("type").asText("");
            if (!"payment_intent.succeeded".equals(type) && !"payment_intent.payment_failed".equals(type)) {
                return null;
            }
            JsonNode paymentIntent = root.path("data").path("object");
            return new PaymentWebhookNotification(
                    "stripe",
                    paymentIntent.path("id").asText(""),
                    paymentIntent.path("metadata").path("orderId").asText(""),
                    fenToYuan(paymentIntent.path("amount").asLong(0)),
                    "payment_intent.succeeded".equals(type) ? "success" : "failed",
                    rawBody == null ? "" : rawBody,
                    Map.of()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("stripe payload parse failed", ex);
        }
    }

    private PaymentWebhookNotification verifyWxpay(PaymentWebhookCandidate candidate, String rawBody, Map<String, String> headers) {
        String timestamp = trimToEmpty(headers.get("wechatpay-timestamp"));
        String nonce = trimToEmpty(headers.get("wechatpay-nonce"));
        String signature = trimToEmpty(headers.get("wechatpay-signature"));
        String serial = trimToEmpty(headers.get("wechatpay-serial"));
        String publicKeyId = trimToEmpty(candidate.config().get("publicKeyId"));
        if (timestamp.isBlank() || nonce.isBlank() || signature.isBlank()) {
            throw new IllegalArgumentException("missing wxpay signature headers");
        }
        if (!publicKeyId.isBlank() && !serial.isBlank() && !publicKeyId.equals(serial)) {
            throw new IllegalArgumentException("wxpay serial mismatch");
        }
        String message = timestamp + "\n" + nonce + "\n" + (rawBody == null ? "" : rawBody) + "\n";
        if (!verifyRsa(message.getBytes(StandardCharsets.UTF_8), signature, candidate.config().get("publicKey"), "SHA256withRSA")) {
            throw new IllegalArgumentException("invalid wxpay signature");
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody == null ? "" : rawBody);
            if (!"TRANSACTION.SUCCESS".equals(root.path("event_type").asText(""))) {
                return null;
            }
            JsonNode resource = root.path("resource");
            String decrypted = decryptWxpayResource(
                    trimToEmpty(candidate.config().get("apiV3Key")),
                    resource.path("associated_data").asText(""),
                    resource.path("nonce").asText(""),
                    resource.path("ciphertext").asText("")
            );
            JsonNode tx = objectMapper.readTree(decrypted);
            Map<String, String> metadata = new HashMap<>();
            putIfPresent(metadata, "appid", tx.path("appid").asText(""));
            putIfPresent(metadata, "mchid", tx.path("mchid").asText(""));
            putIfPresent(metadata, "trade_state", tx.path("trade_state").asText(""));
            putIfPresent(metadata, "currency", tx.path("amount").path("currency").asText(""));
            return new PaymentWebhookNotification(
                    "wxpay",
                    tx.path("transaction_id").asText(""),
                    tx.path("out_trade_no").asText(""),
                    fenToYuan(tx.path("amount").path("total").asLong(0)),
                    "SUCCESS".equals(tx.path("trade_state").asText("")) ? "success" : "failed",
                    rawBody == null ? "" : rawBody,
                    metadata
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("wxpay payload parse failed", ex);
        }
    }

    private String buildEasyPaySignBase(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = trimToEmpty(entry.getValue());
            if ("sign".equalsIgnoreCase(key) || "sign_type".equalsIgnoreCase(key) || value.isBlank()) {
                continue;
            }
            entries.add(Map.entry(key, value));
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("&", parts);
    }

    private String buildAlipaySignContent(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            if (entry.getKey() == null || trimToEmpty(entry.getValue()).isBlank()) {
                continue;
            }
            parts.add(entry.getKey() + "=" + trimToEmpty(entry.getValue()));
        }
        return String.join("&", parts);
    }

    private Map<String, String> parseForm(String rawBody) {
        Map<String, String> values = new HashMap<>();
        if (rawBody == null || rawBody.isBlank()) {
            return values;
        }
        for (String pair : rawBody.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            values.put(
                    urlDecode(key),
                    urlDecode(value)
            );
        }
        return values;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("md5 unavailable", ex);
        }
    }

    private String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : raw) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("hmac unavailable", ex);
        }
    }

    private boolean verifyRsa(byte[] content, String signatureBase64, String pem, String algorithm) {
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(loadPublicKey(pem));
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception ex) {
            throw new IllegalArgumentException("rsa verify failed", ex);
        }
    }

    private PublicKey loadPublicKey(String pem) throws Exception {
        String normalized = trimToEmpty(pem)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    private String decryptWxpayResource(String apiV3Key, String associatedData, String nonce, String ciphertext) {
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(ciphertext);
            int tagLength = 16;
            byte[] actualCipher = cipherBytes;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(tagLength * 8, nonce.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec keySpec = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(actualCipher);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("wxpay decrypt failed", ex);
        }
    }

    private double firstPositiveAmount(String... values) {
        for (String value : values) {
            double parsed = parseAmount(value);
            if (parsed > 0) {
                return parsed;
            }
        }
        return 0;
    }

    private double parseAmount(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0 : Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double fenToYuan(long fen) {
        return fen / 100.0d;
    }

    private void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (!trimToEmpty(value).isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private String firstNonBlank(String left, String right) {
        return !trimToEmpty(left).isBlank() ? left.trim() : trimToEmpty(right).trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String providerKey) {
        return trimToEmpty(providerKey).toLowerCase(Locale.ROOT);
    }

    private StripeSignature parseStripeSignature(String raw) {
        long timestamp = 0L;
        List<String> v1 = new ArrayList<>();
        for (String part : raw.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException ignored) {
                    timestamp = 0L;
                }
            }
            if ("v1".equals(kv[0])) {
                v1.add(kv[1]);
            }
        }
        if (timestamp > 0 && Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) {
            throw new IllegalArgumentException("stripe signature expired");
        }
        return new StripeSignature(timestamp, v1);
    }

    private record StripeSignature(long timestamp, List<String> v1) {
    }
}
