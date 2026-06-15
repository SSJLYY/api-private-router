package org.apiprivaterouter.javabackend.payment.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookCandidate;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookNotification;
import org.apiprivaterouter.javabackend.payment.model.PaymentWebhookOrder;
import org.apiprivaterouter.javabackend.payment.repository.PaymentWebhookRepository;
import org.apiprivaterouter.javabackend.userfund.model.RechargeRequest;
import org.apiprivaterouter.javabackend.userfund.service.FundService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);
    private static final double AMOUNT_TOLERANCE_CNY = 0.01d;

    private final PaymentWebhookRepository repository;
    private final PaymentWebhookProviderVerifier verifier;
    private final JsonHelper jsonHelper;
    private final TransactionTemplate transactionTemplate;
    private final FundService fundService;

    public PaymentWebhookService(
            PaymentWebhookRepository repository,
            PaymentWebhookProviderVerifier verifier,
            JsonHelper jsonHelper,
            TransactionTemplate transactionTemplate,
            FundService fundService
    ) {
        this.repository = repository;
        this.verifier = verifier;
        this.jsonHelper = jsonHelper;
        this.transactionTemplate = transactionTemplate;
        this.fundService = fundService;
    }

    public ResponseEntity<?> handle(String providerKey, HttpServletRequest request, String rawBody, boolean isGet) {
        String normalizedProvider = normalize(providerKey);
        String effectiveBody = rawBody == null ? "" : rawBody;
        String outTradeNo = extractOutTradeNo(normalizedProvider, effectiveBody);

        List<PaymentWebhookCandidate> candidates = resolveCandidates(normalizedProvider, outTradeNo);
        if (candidates.isEmpty()) {
            log.warn("payment webhook provider candidates empty provider={} outTradeNo={}", normalizedProvider, outTradeNo);
            if ("wxpay".equals(normalizedProvider)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("verify failed");
            }
            return successResponse(normalizedProvider);
        }

        Map<String, String> headers = extractHeaders(request);
        PaymentWebhookNotification notification = null;
        String resolvedProviderKey = normalizedProvider;
        Exception lastError = null;
        for (PaymentWebhookCandidate candidate : candidates) {
            try {
                PaymentWebhookNotification verified = verifier.verify(candidate, normalizedProvider, effectiveBody, headers);
                if (verified != null) {
                    notification = verified;
                    resolvedProviderKey = normalize(verified.providerKey());
                } else {
                    resolvedProviderKey = normalize(candidate.providerKey());
                }
                lastError = null;
                break;
            } catch (Exception ex) {
                lastError = ex;
            }
        }

        if (lastError != null) {
            log.error("payment webhook verify failed provider={} outTradeNo={} method={}", normalizedProvider, outTradeNo, request.getMethod(), lastError);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body("verify failed");
        }

        if (notification == null) {
            return successResponse(resolvedProviderKey);
        }

        try {
            handleNotification(notification, resolvedProviderKey);
        } catch (UnknownOrderException ex) {
            log.warn("payment webhook unknown order provider={} orderId={} tradeNo={}", resolvedProviderKey, notification.orderId(), notification.tradeNo());
            return successResponse(resolvedProviderKey);
        } catch (Exception ex) {
            log.error("payment webhook handle failed provider={} orderId={} tradeNo={}", resolvedProviderKey, notification.orderId(), notification.tradeNo(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN).body("handle failed");
        }
        return successResponse(resolvedProviderKey);
    }

    public void handleNotification(PaymentWebhookNotification notification, String resolvedProviderKey) {
        if (!notification.success()) {
            return;
        }
        PaymentWebhookOrder order = findOrder(notification.orderId()).orElseThrow(() -> new UnknownOrderException(notification.orderId()));
        transactionTemplate.executeWithoutResult(status -> confirmAndFulfill(order.id(), notification, resolvedProviderKey));
    }

    protected void confirmAndFulfill(long orderId, PaymentWebhookNotification notification, String resolvedProviderKey) {
        PaymentWebhookOrder order = repository.findOrderByIdForUpdate(orderId)
                .orElseThrow(() -> new UnknownOrderException(notification.orderId()));

        String expectedProviderKey = expectedProviderKey(order);
        if (!expectedProviderKey.isBlank() && !expectedProviderKey.equalsIgnoreCase(normalize(resolvedProviderKey))) {
            writeAudit(order.id(), "PAYMENT_PROVIDER_MISMATCH", Map.of(
                    "expectedProvider", expectedProviderKey,
                    "actualProvider", normalize(resolvedProviderKey),
                    "tradeNo", notification.tradeNo()
            ));
            throw new IllegalStateException("provider mismatch");
        }
        validateNotificationMetadata(order, normalize(resolvedProviderKey), notification.metadata());
        if (notification.amount() <= 0 || Math.abs(notification.amount() - order.payAmount()) > AMOUNT_TOLERANCE_CNY) {
            writeAudit(order.id(), "PAYMENT_AMOUNT_MISMATCH", Map.of(
                    "expected", order.payAmount(),
                    "paid", notification.amount(),
                    "tradeNo", notification.tradeNo()
            ));
            throw new IllegalStateException("amount mismatch");
        }

        int updated = repository.markPaid(order.id(), notification.amount(), notification.tradeNo());
        if (updated == 0) {
            PaymentWebhookOrder reloaded = repository.findOrderByIdForUpdate(order.id()).orElse(order);
            if ("COMPLETED".equalsIgnoreCase(reloaded.status()) || "REFUNDED".equalsIgnoreCase(reloaded.status())) {
                return;
            }
            if ("FAILED".equalsIgnoreCase(reloaded.status())) {
                executeFulfillment(reloaded.id());
                return;
            }
            if ("PAID".equalsIgnoreCase(reloaded.status()) || "RECHARGING".equalsIgnoreCase(reloaded.status())) {
                return;
            }
            if ("EXPIRED".equalsIgnoreCase(reloaded.status())) {
                writeAudit(reloaded.id(), "PAYMENT_AFTER_EXPIRY", Map.of(
                        "status", reloaded.status(),
                        "updatedAt", String.valueOf(reloaded.updatedAt())
                ));
                return;
            }
            return;
        }
        if ("CANCELLED".equalsIgnoreCase(order.status()) || "EXPIRED".equalsIgnoreCase(order.status())) {
            writeAudit(order.id(), "ORDER_RECOVERED", Map.of(
                    "previous_status", order.status(),
                    "tradeNo", notification.tradeNo(),
                    "paidAmount", notification.amount()
            ));
        }
        writeAudit(order.id(), "ORDER_PAID", Map.of(
                "tradeNo", notification.tradeNo(),
                "paidAmount", notification.amount()
        ));
        executeFulfillment(order.id());
    }

    public void retryFulfillment(long orderId) {
        transactionTemplate.executeWithoutResult(status -> executeFulfillment(orderId));
    }

    protected void executeFulfillment(long orderId) {
        PaymentWebhookOrder order = repository.findOrderByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("order not found"));
        if ("subscription".equalsIgnoreCase(order.orderType())) {
            executeSubscriptionFulfillment(order);
            return;
        }
        executeBalanceFulfillment(order);
    }

    private void executeBalanceFulfillment(PaymentWebhookOrder order) {
        if ("COMPLETED".equalsIgnoreCase(order.status())) {
            return;
        }
        if (!"PAID".equalsIgnoreCase(order.status()) && !"FAILED".equalsIgnoreCase(order.status())) {
            throw new IllegalStateException("order cannot fulfill in status " + order.status());
        }
        if (repository.markRecharging(order.id()) == 0) {
            return;
        }
        try {
            String rechargeCode = order.rechargeCode() == null ? "" : order.rechargeCode().trim();
            if (!rechargeCode.isBlank()) {
                repository.ensureRedeemCode(rechargeCode, order.amount(), "payment order " + order.id());
                if (!repository.redeemCodeUsed(rechargeCode)) {
                    repository.findUserBalanceForUpdate(order.userId())
                            .orElseThrow(() -> new IllegalStateException("user not found"));
                    repository.markRedeemCodeUsed(rechargeCode, order.userId());
                }
            }
            syncFundRecharge(order);
            applyAffiliateRebateForOrder(order);
            repository.markCompleted(order.id());
            writeAudit(order.id(), "RECHARGE_SUCCESS", Map.of(
                    "rechargeCode", rechargeCode,
                    "creditedAmount", order.amount(),
                    "payAmount", order.payAmount()
            ));
        } catch (Exception ex) {
            repository.markFailed(order.id(), ex.getMessage());
            writeAudit(order.id(), "FULFILLMENT_FAILED", Map.of("reason", ex.getMessage()));
            throw ex;
        }
    }

    private void syncFundRecharge(PaymentWebhookOrder order) {
        RechargeRequest req = new RechargeRequest(
                order.amount(),
                firstNonBlank(order.paymentType(), "payment"),
                order.outTradeNo(),
                "payment order " + order.id()
        );
        var rechargeResponse = fundService.createRechargeForSystem(order.userId(), req);
        fundService.completeRecharge(rechargeResponse.id(), order.userId());
    }

    private void applyAffiliateRebateForOrder(PaymentWebhookOrder order) {
        if (!"balance".equalsIgnoreCase(order.orderType()) || order.amount() <= 0) {
            return;
        }
        if (!repository.claimAffiliateRebateAudit(order.id(), order.amount())) {
            return;
        }
        try {
            AffiliateSettings settings = loadAffiliateSettings();
            if (!settings.enabled()) {
                updateAffiliateRebateSkipped(order, "affiliate disabled");
                return;
            }
            PaymentWebhookRepository.AffiliateInviteRow invite = repository.findAffiliateInviteRowForUpdate(order.userId()).orElse(null);
            if (invite == null || invite.inviterId() <= 0) {
                updateAffiliateRebateSkipped(order, "no inviter bound or rebate amount <= 0");
                return;
            }
            if (settings.durationDays() > 0
                    && invite.inviteeAffiliateCreatedAt() != null
                    && OffsetDateTime.now().isAfter(invite.inviteeAffiliateCreatedAt().plusDays(settings.durationDays()))) {
                updateAffiliateRebateSkipped(order, "affiliate rebate duration expired");
                return;
            }
            double ratePercent = invite.inviterRebateRatePercent() == null
                    ? settings.rebateRatePercent()
                    : clamp(invite.inviterRebateRatePercent(), 0.0d, 100.0d);
            double rebateAmount = roundTo(order.amount() * ratePercent / 100.0d, 8);
            if (settings.perInviteeCap() > 0 && rebateAmount > 0) {
                double accrued = repository.sumAffiliateAccruedFromInvitee(invite.inviterId(), order.userId());
                double remaining = settings.perInviteeCap() - accrued;
                if (remaining <= 0) {
                    rebateAmount = 0.0d;
                } else if (rebateAmount > remaining) {
                    rebateAmount = roundTo(remaining, 8);
                }
            }
            if (rebateAmount <= 0) {
                updateAffiliateRebateSkipped(order, "no inviter bound or rebate amount <= 0");
                return;
            }
            if (!repository.accrueAffiliateRebate(invite.inviterId(), order.userId(), order.id(), rebateAmount, settings.freezeHours())) {
                updateAffiliateRebateSkipped(order, "inviter profile missing");
                return;
            }
            repository.updateAffiliateRebateAudit(order.id(), "AFFILIATE_REBATE_APPLIED", Map.of(
                    "baseAmount", order.amount(),
                    "rebateAmount", rebateAmount
            ));
        } catch (RuntimeException ex) {
            log.error("affiliate rebate failed for payment order {}: {}", order.id(), ex.getMessage(), ex);
            writeAudit(order.id(), "AFFILIATE_REBATE_FAILED", Map.of("error", ex.getMessage() == null ? "" : ex.getMessage()));
        }
    }

    private void updateAffiliateRebateSkipped(PaymentWebhookOrder order, String reason) {
        repository.updateAffiliateRebateAudit(order.id(), "AFFILIATE_REBATE_SKIPPED", Map.of(
                "baseAmount", order.amount(),
                "reason", reason
        ));
    }

    private AffiliateSettings loadAffiliateSettings() {
        Map<String, String> settings = repository.getSettings(
                "affiliate_enabled",
                "affiliate_rebate_rate",
                "affiliate_rebate_freeze_hours",
                "affiliate_rebate_duration_days",
                "affiliate_rebate_per_invitee_cap"
        );
        return new AffiliateSettings(
                parseBoolean(settings.get("affiliate_enabled"), false),
                clamp(parseDouble(settings.get("affiliate_rebate_rate"), 20.0d), 0.0d, 100.0d),
                clampInt(parseInt(settings.get("affiliate_rebate_freeze_hours"), 0), 0, 720),
                clampInt(parseInt(settings.get("affiliate_rebate_duration_days"), 0), 0, 3650),
                Math.max(parseDouble(settings.get("affiliate_rebate_per_invitee_cap"), 0.0d), 0.0d)
        );
    }

    private void executeSubscriptionFulfillment(PaymentWebhookOrder order) {
        if ("COMPLETED".equalsIgnoreCase(order.status())) {
            return;
        }
        if (!"PAID".equalsIgnoreCase(order.status()) && !"FAILED".equalsIgnoreCase(order.status())) {
            throw new IllegalStateException("order cannot fulfill in status " + order.status());
        }
        if (order.subscriptionGroupId() == null || order.subscriptionDays() == null) {
            throw new IllegalStateException("missing subscription info");
        }
        if (repository.markRecharging(order.id()) == 0) {
            return;
        }
        try {
            if (!repository.subscriptionGroupActive(order.subscriptionGroupId())) {
                throw new IllegalStateException("subscription group inactive");
            }
            if (repository.hasAuditLog(order.id(), "SUBSCRIPTION_SUCCESS")) {
                repository.markCompleted(order.id());
                return;
            }
            OffsetDateTime now = OffsetDateTime.now();
            String note = "payment order " + order.id();
            Optional<PaymentWebhookRepository.SubscriptionRow> existing = repository.findLatestSubscriptionForUpdate(
                    order.userId(),
                    order.subscriptionGroupId()
            );
            if (existing.isEmpty()) {
                repository.createSubscription(
                        order.userId(),
                        order.subscriptionGroupId(),
                        now,
                        now.plusDays(order.subscriptionDays()),
                        note
                );
            } else {
                PaymentWebhookRepository.SubscriptionRow sub = existing.get();
                OffsetDateTime expiresAt = sub.expiresAt() != null && sub.expiresAt().isAfter(now)
                        ? sub.expiresAt().plusDays(order.subscriptionDays())
                        : now.plusDays(order.subscriptionDays());
                String notes = sub.notes() == null || sub.notes().isBlank() ? note : sub.notes() + "\n" + note;
                repository.updateSubscription(sub.id(), expiresAt, "active", notes);
            }
            repository.markCompleted(order.id());
            writeAudit(order.id(), "SUBSCRIPTION_SUCCESS", Map.of(
                    "groupId", order.subscriptionGroupId(),
                    "days", order.subscriptionDays()
            ));
        } catch (Exception ex) {
            repository.markFailed(order.id(), ex.getMessage());
            writeAudit(order.id(), "FULFILLMENT_FAILED", Map.of("reason", ex.getMessage()));
            throw ex;
        }
    }

    private void validateNotificationMetadata(PaymentWebhookOrder order, String providerKey, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        Map<String, Object> snapshot = order.providerSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        if ("easypay".equals(providerKey)) {
            assertMatchesAnySnapshotKey(snapshot, metadata, "pid", "merchant_id", "pid");
            return;
        }
        if ("alipay".equals(providerKey)) {
            assertMatchesAnySnapshotKey(snapshot, metadata, "app_id", "merchant_app_id", "appId");
            return;
        }
        if ("wxpay".equals(providerKey)) {
            assertMatchesAnySnapshotKey(snapshot, metadata, "appid", "merchant_app_id", "appId");
            assertMatchesAnySnapshotKey(snapshot, metadata, "mchid", "merchant_id", "mchId");
            assertMatchesAnySnapshotKey(snapshot, metadata, "currency", "currency");
            String tradeState = metadata.getOrDefault("trade_state", "").trim();
            if (!tradeState.isBlank() && !"SUCCESS".equalsIgnoreCase(tradeState)) {
                throw new IllegalStateException("provider metadata mismatch");
            }
        }
    }

    private void assertMatchesAnySnapshotKey(Map<String, Object> snapshot, Map<String, String> metadata, String metadataKey, String... snapshotKeys) {
        String expected = "";
        for (String snapshotKey : snapshotKeys) {
            expected = stringValue(snapshot.get(snapshotKey));
            if (!expected.isBlank()) {
                break;
            }
        }
        String actual = metadata.getOrDefault(metadataKey, "");
        if (!expected.isBlank() && !actual.isBlank() && !expected.equals(actual)) {
            throw new IllegalStateException("provider metadata mismatch");
        }
    }

    private Optional<PaymentWebhookOrder> findOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        Optional<PaymentWebhookOrder> byOutTradeNo = repository.findOrderByOutTradeNo(orderId);
        if (byOutTradeNo.isPresent()) {
            return byOutTradeNo;
        }
        if (orderId.startsWith("rh_")) {
            try {
                return repository.findOrderById(Long.parseLong(orderId.substring("rh_".length())));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private List<PaymentWebhookCandidate> resolveCandidates(String providerKey, String outTradeNo) {
        Optional<PaymentWebhookOrder> order = repository.findOrderByOutTradeNo(outTradeNo);
        if (order.isPresent()) {
            PaymentWebhookOrder paymentOrder = order.get();
            if (paymentOrder.providerInstanceId() != null && !paymentOrder.providerInstanceId().isBlank()) {
                return repository.findCandidateByInstanceId(paymentOrder.providerInstanceId()).stream().toList();
            }
            if ("wxpay".equals(providerKey)) {
                return repository.findEnabledCandidatesByProviderKey(providerKey);
            }
            if (repository.countEnabledCandidatesByProviderKey(providerKey) <= 1) {
                return repository.findEnabledCandidatesByProviderKey(providerKey);
            }
            return List.of();
        }
        if ("wxpay".equals(providerKey)) {
            return repository.findEnabledCandidatesByProviderKey(providerKey);
        }
        if (repository.countEnabledCandidatesByProviderKey(providerKey) <= 1) {
            return repository.findEnabledCandidatesByProviderKey(providerKey);
        }
        return List.of();
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }

    private String extractOutTradeNo(String providerKey, String rawBody) {
        if ("easypay".equals(providerKey) || "alipay".equals(providerKey)) {
            Map<String, String> values = new HashMap<>();
            if (rawBody != null && !rawBody.isBlank()) {
                for (String pair : rawBody.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx <= 0) {
                        continue;
                    }
                    values.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
                }
            }
            return values.getOrDefault("out_trade_no", "");
        }
        return "";
    }

    private String urlDecode(String value) {
        return java.net.URLDecoder.decode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String expectedProviderKey(PaymentWebhookOrder order) {
        if (order.providerKey() != null && !order.providerKey().isBlank()) {
            return normalize(order.providerKey());
        }
        if (order.paymentType() != null && !order.paymentType().isBlank()) {
            return normalize(order.paymentType());
        }
        return "";
    }

    private void writeAudit(long orderId, String action, Map<String, Object> detail) {
        repository.insertAuditLog(orderId, action, jsonHelper.writeJson(detail), "system");
    }

    private ResponseEntity<?> successResponse(String providerKey) {
        String normalized = normalize(providerKey);
        if ("wxpay".equals(normalized)) {
            return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
        }
        if ("stripe".equals(normalized)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("success");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String normalize(String providerKey) {
        return providerKey == null ? "" : providerKey.trim().toLowerCase(Locale.ROOT);
    }

    private boolean parseBoolean(String raw, boolean defaultValue) {
        return raw == null || raw.isBlank() ? defaultValue : Boolean.parseBoolean(raw);
    }

    private int parseInt(String raw, int defaultValue) {
        try {
            return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private double parseDouble(String raw, double defaultValue) {
        try {
            return raw == null || raw.isBlank() ? defaultValue : Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private double roundTo(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    static class UnknownOrderException extends RuntimeException {
        UnknownOrderException(String orderId) {
            super(orderId);
        }
    }

    private record AffiliateSettings(
            boolean enabled,
            double rebateRatePercent,
            int freezeHours,
            int durationDays,
            double perInviteeCap
    ) {
    }
}
