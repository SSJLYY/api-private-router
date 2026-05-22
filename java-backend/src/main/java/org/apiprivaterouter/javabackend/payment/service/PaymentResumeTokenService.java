package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class PaymentResumeTokenService {

    private static final String TOKEN_TYPE_WECHAT_PAYMENT_RESUME = "wechat_payment_resume";
    private static final long PAYMENT_RESUME_TTL_SECONDS = 24L * 60L * 60L;
    private static final long WECHAT_PAYMENT_RESUME_TTL_SECONDS = 15L * 60L;

    private final ObjectMapper objectMapper;
    private final Environment environment;

    public PaymentResumeTokenService(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    public String createToken(ResumeTokenClaims claims) {
        List<byte[]> keys = resolveKeys();
        if (keys.isEmpty()) {
            throw new StructuredApiErrorException(
                    503,
                    "PAYMENT_RESUME_NOT_CONFIGURED",
                    "payment resume tokens require a configured signing key"
            );
        }
        ResumeTokenClaims normalized = claims.normalizeWithDefaults();
        if (normalized.orderId() <= 0) {
            throw new IllegalArgumentException("resume token requires order id");
        }
        return createSignedToken(normalized, keys.get(0));
    }

    public ResumeTokenClaims parseToken(String token) {
        ResumeTokenClaims claims = parseSignedToken(token, ResumeTokenClaims.class, "INVALID_RESUME_TOKEN");
        if (claims.orderId() <= 0) {
            throw new StructuredApiErrorException(400, "INVALID_RESUME_TOKEN", "resume token missing order id");
        }
        validateExpiry(claims.expiresAt(), "INVALID_RESUME_TOKEN", "resume token has expired");
        return claims.normalizeWithDefaults();
    }

    public String createWeChatPaymentResumeToken(WeChatPaymentResumeClaims claims) {
        List<byte[]> keys = resolveKeys();
        if (keys.isEmpty()) {
            throw new StructuredApiErrorException(
                    503,
                    "PAYMENT_RESUME_NOT_CONFIGURED",
                    "payment resume tokens require a configured signing key"
            );
        }
        WeChatPaymentResumeClaims normalized = claims.normalizeWithDefaults();
        if (normalized.openid().isBlank()) {
            throw new IllegalArgumentException("wechat payment resume token requires openid");
        }
        return createSignedToken(normalized, keys.get(0));
    }

    public WeChatPaymentResumeClaims parseWeChatPaymentResumeToken(String token) {
        WeChatPaymentResumeClaims claims = parseSignedToken(
                token,
                WeChatPaymentResumeClaims.class,
                "INVALID_WECHAT_PAYMENT_RESUME_TOKEN"
        );
        if (!TOKEN_TYPE_WECHAT_PAYMENT_RESUME.equals(claims.tokenType())) {
            throw new StructuredApiErrorException(
                    400,
                    "INVALID_WECHAT_PAYMENT_RESUME_TOKEN",
                    "wechat payment resume token type mismatch"
            );
        }
        if (claims.openid() == null || claims.openid().trim().isEmpty()) {
            throw new StructuredApiErrorException(
                    400,
                    "INVALID_WECHAT_PAYMENT_RESUME_TOKEN",
                    "wechat payment resume token missing openid"
            );
        }
        validateExpiry(
                claims.expiresAt(),
                "INVALID_WECHAT_PAYMENT_RESUME_TOKEN",
                "wechat payment resume token has expired"
        );
        return claims.normalizeWithDefaults();
    }

    public boolean isSigningConfigured() {
        return !resolveKeys().isEmpty();
    }

    private List<byte[]> resolveKeys() {
        Set<String> rawKeys = new LinkedHashSet<>();
        addIfPresent(rawKeys, environment.getProperty("PAYMENT_RESUME_SIGNING_KEY"));
        addIfPresent(rawKeys, environment.getProperty("payment.resume.signing-key"));
        addIfPresent(rawKeys, environment.getProperty("payment_resume_signing_key"));
        addIfPresent(rawKeys, environment.getProperty("TOTP_ENCRYPTION_KEY"));
        addIfPresent(rawKeys, environment.getProperty("totp.encryption-key"));
        addIfPresent(rawKeys, environment.getProperty("totp_encryption_key"));

        List<byte[]> keys = new ArrayList<>();
        for (String rawKey : rawKeys) {
            byte[] bytes = parseKey(rawKey);
            if (bytes.length > 0) {
                keys.add(bytes);
            }
        }
        return keys;
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value.trim());
        }
    }

    private byte[] parseKey(String rawKey) {
        String trimmed = rawKey == null ? "" : rawKey.trim();
        if (trimmed.isEmpty()) {
            return new byte[0];
        }
        if (trimmed.length() >= 64 && trimmed.length() % 2 == 0) {
            try {
                return HexFormat.of().parseHex(trimmed);
            } catch (IllegalArgumentException ignored) {
                return trimmed.getBytes(StandardCharsets.UTF_8);
            }
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    private <T> T parseSignedToken(String token, Class<T> type, String invalidCode) {
        List<byte[]> keys = resolveKeys();
        if (keys.isEmpty()) {
            throw new StructuredApiErrorException(
                    503,
                    "PAYMENT_RESUME_NOT_CONFIGURED",
                    "payment resume tokens require a configured signing key"
            );
        }
        String raw = token == null ? "" : token.trim();
        String[] parts = raw.split("\\.");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new StructuredApiErrorException(400, invalidCode, "resume token is malformed");
        }
        String payload = parts[0];
        String signature = parts[1];
        if (!verifySignature(payload, signature, keys)) {
            throw new StructuredApiErrorException(400, invalidCode, "resume token signature mismatch");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new StructuredApiErrorException(400, invalidCode, "resume token payload is malformed");
        }
        try {
            return objectMapper.readValue(decoded, type);
        } catch (IOException ex) {
            throw new StructuredApiErrorException(
                    400,
                    invalidCode,
                    invalidCode.equals("INVALID_WECHAT_PAYMENT_RESUME_TOKEN")
                            ? "wechat payment resume token payload is invalid"
                            : "resume token payload is invalid"
            );
        }
    }

    private boolean verifySignature(String payload, String signature, List<byte[]> keys) {
        for (byte[] key : keys) {
            if (Objects.equals(signature, sign(payload, key))) {
                return true;
            }
        }
        return false;
    }

    private String createSignedToken(Object claims, byte[] key) {
        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(claims);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to encode resume token claims", ex);
        }
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        return payload + "." + sign(payload, key);
    }

    private String sign(String payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign payment resume token", ex);
        }
    }

    private void validateExpiry(Long expiresAt, String code, String message) {
        if (expiresAt == null || expiresAt <= 0) {
            return;
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new StructuredApiErrorException(400, code, message);
        }
    }

    public record ResumeTokenClaims(
            @JsonProperty("oid") long orderId,
            @JsonProperty("uid") Long userId,
            @JsonProperty("pi") String providerInstanceId,
            @JsonProperty("pk") String providerKey,
            @JsonProperty("pt") String paymentType,
            @JsonProperty("ru") String canonicalReturnUrl,
            @JsonProperty("iat") Long issuedAt,
            @JsonProperty("exp") Long expiresAt
    ) {
        public ResumeTokenClaims normalizeWithDefaults() {
            long now = Instant.now().getEpochSecond();
            String normalizedPaymentType = normalizeVisibleMethod(paymentType);
            return new ResumeTokenClaims(
                    orderId,
                    userId,
                    blankToNull(providerInstanceId),
                    blankToNull(providerKey),
                    normalizedPaymentType,
                    blankToNull(canonicalReturnUrl),
                    issuedAt == null || issuedAt <= 0 ? now : issuedAt,
                    expiresAt == null || expiresAt <= 0 ? now + PAYMENT_RESUME_TTL_SECONDS : expiresAt
            );
        }
    }

    public record WeChatPaymentResumeClaims(
            @JsonProperty("tk") String tokenType,
            @JsonProperty("openid") String openid,
            @JsonProperty("pt") String paymentType,
            @JsonProperty("amt") String amount,
            @JsonProperty("ot") String orderType,
            @JsonProperty("pid") Long planId,
            @JsonProperty("rd") String redirectTo,
            @JsonProperty("scp") String scope,
            @JsonProperty("iat") Long issuedAt,
            @JsonProperty("exp") Long expiresAt
    ) {
        public WeChatPaymentResumeClaims normalizeWithDefaults() {
            long now = Instant.now().getEpochSecond();
            String normalizedPaymentType = normalizeVisibleMethod(paymentType);
            if (normalizedPaymentType == null || normalizedPaymentType.isBlank()) {
                normalizedPaymentType = "wxpay";
            }
            String normalizedOrderType = orderType == null || orderType.trim().isEmpty() ? "balance" : orderType.trim();
            String normalizedScope = normalizeScope(scope);
            return new WeChatPaymentResumeClaims(
                    TOKEN_TYPE_WECHAT_PAYMENT_RESUME,
                    openid == null ? "" : openid.trim(),
                    normalizedPaymentType,
                    blankToNull(amount),
                    normalizedOrderType,
                    planId != null && planId > 0 ? planId : null,
                    blankToNull(redirectTo),
                    normalizedScope,
                    issuedAt == null || issuedAt <= 0 ? now : issuedAt,
                    expiresAt == null || expiresAt <= 0 ? now + WECHAT_PAYMENT_RESUME_TTL_SECONDS : expiresAt
            );
        }
    }

    public static String normalizeVisibleMethod(String paymentType) {
        String raw = paymentType == null ? "" : paymentType.trim().toLowerCase(Locale.ROOT);
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

    public static String normalizeScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return "snsapi_base";
        }
        for (String part : scope.split("[,\\s]+")) {
            String candidate = part.trim();
            if ("snsapi_userinfo".equals(candidate)) {
                return "snsapi_userinfo";
            }
            if ("snsapi_base".equals(candidate)) {
                return "snsapi_base";
            }
        }
        return "snsapi_base";
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
