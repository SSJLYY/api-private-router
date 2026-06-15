package org.apiprivaterouter.javabackend.admin.payment.service;

import org.apiprivaterouter.javabackend.admin.payment.model.AdminPaymentConfigResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminPaymentDashboardResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.PlanUpsertRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.ProviderUpsertRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminRefundOrderRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminRefundOrderResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.UpdateAdminPaymentConfigRequest;
import org.apiprivaterouter.javabackend.admin.payment.repository.AdminPaymentRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.model.PaymentChannelResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.SubscriptionPlanResponse;
import org.apiprivaterouter.javabackend.payment.service.AlipayPaymentClient;
import org.apiprivaterouter.javabackend.payment.service.EasyPayPaymentClient;
import org.apiprivaterouter.javabackend.payment.service.PaymentWebhookService;
import org.apiprivaterouter.javabackend.payment.service.PaymentService;
import org.apiprivaterouter.javabackend.payment.service.StripePaymentClient;
import org.apiprivaterouter.javabackend.payment.service.WxpayPaymentClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminPaymentService {

    private final AdminPaymentRepository repository;
    private final StripePaymentClient stripePaymentClient;
    private final AlipayPaymentClient alipayPaymentClient;
    private final WxpayPaymentClient wxpayPaymentClient;
    private final EasyPayPaymentClient easyPayPaymentClient;
    private final PaymentWebhookService paymentWebhookService;
    private final PaymentService paymentService;
    private final TransactionTemplate transactionTemplate;

    public AdminPaymentService(
            AdminPaymentRepository repository,
            StripePaymentClient stripePaymentClient,
            AlipayPaymentClient alipayPaymentClient,
            WxpayPaymentClient wxpayPaymentClient,
            EasyPayPaymentClient easyPayPaymentClient,
            PaymentWebhookService paymentWebhookService,
            PaymentService paymentService,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.stripePaymentClient = stripePaymentClient;
        this.alipayPaymentClient = alipayPaymentClient;
        this.wxpayPaymentClient = wxpayPaymentClient;
        this.easyPayPaymentClient = easyPayPaymentClient;
        this.paymentWebhookService = paymentWebhookService;
        this.paymentService = paymentService;
        this.transactionTemplate = transactionTemplate;
    }

    public AdminPaymentConfigResponse getConfig() {
        return repository.loadConfig();
    }

    public Map<String, String> updateConfig(UpdateAdminPaymentConfigRequest request) {
        repository.updateConfig(request);
        return Map.of("message", "updated");
    }

    public AdminPaymentDashboardResponse getDashboard(int days) {
        return repository.loadDashboard(days);
    }

    public PageResponse<PaymentOrderResponse> listOrders(int page, int pageSize, Long userId, String status, String paymentType, String orderType, String keyword) {
        return repository.listOrders(page, pageSize, userId, status, paymentType, orderType, keyword);
    }

    public Map<String, Object> getOrderDetail(long id) {
        PaymentOrderResponse order = repository.getOrder(id).orElseThrow(() -> new IllegalArgumentException("order not found"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order", order);
        payload.put("auditLogs", repository.getOrderAuditLogs(id));
        return payload;
    }

    @Transactional
    public PaymentOrderResponse cancelOrder(long id) {
        PaymentOrderResponse before = repository.getOrder(id).orElseThrow(() -> new IllegalArgumentException("order not found"));
        if (!"PENDING".equalsIgnoreCase(before.status())) {
            throw new IllegalArgumentException("only pending orders can be cancelled");
        }
        PaymentOrderResponse reconciled = paymentService.reconcileOrderForSystem(before);
        if (!"PENDING".equalsIgnoreCase(reconciled.status())) {
            return reconciled;
        }
        before = reconciled;
        if ("stripe".equalsIgnoreCase(before.provider_key()) || "stripe".equalsIgnoreCase(before.payment_type())) {
            ProviderInstanceResponse provider = repository.getProviderByInstanceId(before.provider_instance_id())
                    .orElseThrow(() -> new IllegalArgumentException("provider not found"));
            stripePaymentClient.cancelPayment(provider, before);
        } else if ("alipay".equalsIgnoreCase(before.provider_key()) || "alipay".equalsIgnoreCase(before.payment_type())) {
            ProviderInstanceResponse provider = repository.getProviderByInstanceId(before.provider_instance_id())
                    .orElseThrow(() -> new IllegalArgumentException("provider not found"));
            alipayPaymentClient.cancelPayment(provider, before);
        } else if ("wxpay".equalsIgnoreCase(before.provider_key()) || "wxpay".equalsIgnoreCase(before.payment_type())) {
            ProviderInstanceResponse provider = repository.getProviderByInstanceId(before.provider_instance_id())
                    .orElseThrow(() -> new IllegalArgumentException("provider not found"));
            wxpayPaymentClient.cancelPayment(provider, before);
        }
        PaymentOrderResponse cancelled = repository.cancelOrder(id).orElseThrow(() -> new IllegalArgumentException("order not found"));
        repository.insertAuditLog(id, "ORDER_CANCELLED", Map.of("detail", "admin cancelled order"), "admin");
        return cancelled;
    }

    @Transactional
    public PaymentOrderResponse retryOrder(long id) {
        PaymentOrderResponse before = repository.getOrder(id).orElseThrow(() -> new IllegalArgumentException("order not found"));
        if (!"FAILED".equalsIgnoreCase(before.status())) {
            throw new IllegalArgumentException("only failed orders can be retried");
        }
        PaymentOrderResponse retrying = repository.retryOrder(id).orElseThrow(() -> new IllegalArgumentException("order not found"));
        paymentWebhookService.retryFulfillment(id);
        return repository.getOrder(id).orElse(retrying);
    }

    public AdminRefundOrderResponse refundOrder(long id, AdminRefundOrderRequest request) {
        PaymentOrderResponse order = repository.getRefundableOrder(id).orElseThrow(() -> new IllegalArgumentException("order not found"));
        String reason = normalizeRefundReason(request.reason(), order);
        if (!"balance".equalsIgnoreCase(order.order_type()) && !"subscription".equalsIgnoreCase(order.order_type())) {
            throw new StructuredApiErrorException(400, "INVALID_ORDER_TYPE", "order type does not allow refund");
        }
        Map<String, Object> providerSnapshot = repository.loadProviderSnapshot(order.id());
        ProviderInstanceResponse provider = resolveRefundProvider(order, providerSnapshot);
        if (!provider.refund_enabled()) {
            throw new StructuredApiErrorException(403, "REFUND_DISABLED", "refund is not enabled for this provider");
        }
        validateRefundProviderSnapshot(order, provider, providerSnapshot);
        double amount = normalizeRefundAmount(request.amount(), order.amount());
        double gatewayAmount = calculateGatewayRefundAmount(order.amount(), order.pay_amount(), amount);
        boolean force = Boolean.TRUE.equals(request.force());
        boolean deduct = Boolean.TRUE.equals(request.deduct_balance());
        RefundPreparation preparation = prepareAndLockRefund(order, amount, force, deduct);
        if (preparation.earlyResponse() != null) {
            return preparation.earlyResponse();
        }
        RefundDeduction deduction = preparation.deduction();
        try {
            invokeProviderRefund(provider, order, gatewayAmount, reason);
        } catch (RuntimeException ex) {
            return handleRefundGatewayFailure(order, deduction, ex);
        }
        PaymentOrderResponse refunded = repository.markRefundSucceeded(id, amount, reason, force)
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        repository.insertAuditLog(id, "REFUND_SUCCESS", Map.of(
                "refundAmount", amount,
                "gatewayAmount", gatewayAmount,
                "reason", reason,
                "balanceDeducted", deduction.balanceAmount(),
                "subDaysDeducted", deduction.subscriptionDays(),
                "force", force
        ), "admin");
        return AdminRefundOrderResponse.success(deduction.balanceAmount(), deduction.subscriptionDays(), refunded);
    }

    public List<SubscriptionPlanResponse> listPlans() {
        return repository.listPlans();
    }

    public SubscriptionPlanResponse createPlan(PlanUpsertRequest request) {
        return repository.createPlan(normalizePlan(request, false));
    }

    public SubscriptionPlanResponse updatePlan(long id, PlanUpsertRequest request) {
        return repository.updatePlan(id, normalizePlan(request, true));
    }

    @Transactional
    public Map<String, String> deletePlan(long id) {
        repository.listPlans().stream()
                .filter(item -> item.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        long activeOrders = repository.countOrdersByPlanId(id);
        if (activeOrders > 0) {
            throw new IllegalArgumentException("plan has active or refundable orders");
        }
        repository.deletePlan(id);
        return Map.of("message", "deleted");
    }

    public List<ProviderInstanceResponse> listProviders() {
        return repository.listProvidersForAdmin();
    }

    public ProviderInstanceResponse createProvider(ProviderUpsertRequest request) {
        ProviderUpsertRequest normalized = normalizeProvider(request, false);
        validateProviderForCreate(normalized);
        return repository.createProvider(normalized);
    }

    public ProviderInstanceResponse updateProvider(long id, ProviderUpsertRequest request) {
        ProviderUpsertRequest normalized = normalizeProvider(request, true);
        ProviderUpsertRequest merged = repository.mergeProviderPatch(id, normalized);
        validateProviderMutation(id, merged, normalized);
        return repository.getProviderForAdmin(repository.updateProvider(id, merged).id())
                .orElseThrow(() -> new IllegalArgumentException("provider not found"));
    }

    @Transactional
    public Map<String, String> deleteProvider(long id) {
        long activeOrders = repository.countActiveOrdersByProviderId(id);
        if (activeOrders > 0) {
            throw new IllegalArgumentException("provider has active or refundable orders");
        }
        repository.deleteProvider(id);
        return Map.of("message", "deleted");
    }

    public List<PaymentChannelResponse> listChannels() {
        return repository.listChannels();
    }

    public PaymentChannelResponse createChannel(ProviderUpsertRequest request) {
        ProviderUpsertRequest normalized = normalizeProvider(request, false);
        validateProviderForCreate(normalized);
        return repository.createChannel(normalized);
    }

    public PaymentChannelResponse updateChannel(long id, ProviderUpsertRequest request) {
        ProviderUpsertRequest normalized = normalizeProvider(request, true);
        ProviderUpsertRequest merged = repository.mergeProviderPatch(id, normalized);
        validateProviderMutation(id, merged, normalized);
        return repository.updateChannel(id, merged);
    }

    @Transactional
    public Map<String, String> deleteChannel(long id) {
        long activeOrders = repository.countActiveOrdersByProviderId(id);
        if (activeOrders > 0) {
            throw new IllegalArgumentException("channel has active or refundable orders");
        }
        repository.deleteChannel(id);
        return Map.of("message", "deleted");
    }

    private PlanUpsertRequest normalizePlan(PlanUpsertRequest request, boolean patch) {
        Long groupId = patch ? request.group_id() : requirePositive(request.group_id(), "group_id");
        String name = patch ? trimToNull(request.name()) : requireText(request.name(), "name");
        String description = patch ? trimToNull(request.description()) : requireText(request.description(), "description");
        Double price = patch ? request.price() : requirePositive(request.price(), "price");
        Double originalPrice = request.original_price() != null && request.original_price() <= 0 ? null : request.original_price();
        Integer validityDays = patch ? normalizePositiveInt(request.validity_days()) : requirePositive(request.validity_days(), "validity_days");
        String validityUnit = normalizeValidityUnit(patch ? trimToNull(request.validity_unit()) : requireText(request.validity_unit(), "validity_unit"), patch);
        String features = request.features() == null ? null : request.features().trim();
        String productName = request.product_name() == null ? null : request.product_name().trim();
        Boolean forSale = request.for_sale();
        Integer sortOrder = normalizeNonNegativeInt(request.sort_order());
        return new PlanUpsertRequest(groupId, name, description, price, originalPrice, validityDays, validityUnit, features, productName, forSale, sortOrder);
    }

    private ProviderUpsertRequest normalizeProvider(ProviderUpsertRequest request, boolean patch) {
        String providerKey = patch ? trimToNull(request.provider_key()) : requireText(request.provider_key(), "provider_key");
        String name = patch ? trimToNull(request.name()) : requireText(request.name(), "name");
        List<String> supportedTypes = request.supported_types() == null ? null : request.supported_types().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        Boolean refundEnabled = request.refund_enabled();
        Boolean allowUserRefund = request.allow_user_refund();
        if (Boolean.FALSE.equals(refundEnabled)) {
            allowUserRefund = false;
        }
        String paymentMode = trimToNull(request.payment_mode());
        return new ProviderUpsertRequest(
                providerKey,
                name,
                request.config(),
                supportedTypes,
                request.enabled(),
                paymentMode,
                normalizeNonNegativeInt(request.sort_order()),
                request.limits() == null ? null : request.limits().trim(),
                refundEnabled,
                allowUserRefund
        );
    }

    private void validateProviderMutation(long providerId, ProviderUpsertRequest merged, ProviderUpsertRequest patch) {
        if (merged.supported_types() == null || merged.supported_types().isEmpty()) {
            throw new IllegalArgumentException("supported_types is required");
        }
        if (Boolean.TRUE.equals(merged.allow_user_refund()) && !Boolean.TRUE.equals(merged.refund_enabled())) {
            throw new IllegalArgumentException("allow_user_refund requires refund_enabled");
        }
        if (patch.provider_key() != null || patch.config() != null) {
            validateProviderConfig(merged);
        }
        long activeOrders = repository.countActiveOrdersByProviderId(providerId);
        if (activeOrders <= 0) {
            return;
        }
        if (patch.provider_key() != null) {
            throw new IllegalArgumentException("provider_key cannot change while orders are active");
        }
        if (patch.supported_types() != null && patch.supported_types().isEmpty()) {
            throw new IllegalArgumentException("supported_types cannot be empty while orders are active");
        }
        if (Boolean.FALSE.equals(merged.enabled())) {
            throw new IllegalArgumentException("cannot disable provider while orders are active");
        }
    }

    private void validateProviderForCreate(ProviderUpsertRequest request) {
        if (request.supported_types() == null || request.supported_types().isEmpty()) {
            throw new IllegalArgumentException("supported_types is required");
        }
        if (Boolean.TRUE.equals(request.allow_user_refund()) && !Boolean.TRUE.equals(request.refund_enabled())) {
            throw new IllegalArgumentException("allow_user_refund requires refund_enabled");
        }
        if ("easypay".equalsIgnoreCase(request.provider_key()) && trimToNull(request.payment_mode()) == null) {
            throw new IllegalArgumentException("payment_mode is required for easypay");
        }
        validateProviderConfig(request);
    }

    private void validateProviderConfig(ProviderUpsertRequest request) {
        String providerKey = request.provider_key() == null ? "" : request.provider_key().trim().toLowerCase(Locale.ROOT);
        if (!"wxpay".equals(providerKey)) {
            return;
        }
        Map<String, String> config = request.config() == null ? Map.of() : request.config();
        for (String key : List.of("appId", "mchId", "privateKey", "apiV3Key", "certSerial", "publicKey", "publicKeyId")) {
            if (trimToNull(config.get(key)) == null) {
                throw new IllegalArgumentException("wxpay config missing required key: " + key);
            }
        }
        String apiV3Key = trimToNull(config.get("apiV3Key"));
        if (apiV3Key == null || apiV3Key.length() != 32) {
            throw new IllegalArgumentException("wxpay apiV3Key must be 32 characters");
        }
    }

    private double normalizeRefundAmount(double requestedAmount, double orderAmount) {
        if (Double.isNaN(requestedAmount) || Double.isInfinite(requestedAmount)) {
            throw new StructuredApiErrorException(400, "INVALID_AMOUNT", "invalid refund amount");
        }
        double amount = requestedAmount > 0 ? requestedAmount : orderAmount;
        if (amount <= 0) {
            throw new StructuredApiErrorException(400, "INVALID_AMOUNT", "amount must be > 0");
        }
        if (amount - orderAmount > 0.01d) {
            throw new StructuredApiErrorException(400, "REFUND_AMOUNT_EXCEEDED", "refund amount exceeds recharge");
        }
        return roundAmount(amount);
    }

    private double calculateGatewayRefundAmount(double orderAmount, double payAmount, double refundAmount) {
        if (orderAmount <= 0 || refundAmount >= orderAmount - 0.01d) {
            return roundAmount(payAmount > 0 ? payAmount : refundAmount);
        }
        double basePayAmount = payAmount > 0 ? payAmount : orderAmount;
        return roundAmount(basePayAmount * refundAmount / orderAmount);
    }

    private RefundPreparation prepareAndLockRefund(PaymentOrderResponse order, double amount, boolean force, boolean deduct) {
        return transactionTemplate.execute(status -> {
            if (repository.markRefunding(order.id()) == 0) {
                throw new StructuredApiErrorException(409, "CONFLICT", "order status changed");
            }
            RefundDeduction deduction = RefundDeduction.none(order.status());
            if (!deduct) {
                return RefundPreparation.deduction(deduction);
            }
            if ("subscription".equalsIgnoreCase(order.order_type())) {
                deduction = deductSubscriptionForRefund(order, force);
            } else {
                deduction = deductBalanceForRefund(order, amount, force);
            }
            if (deduction.requireForceWarning() != null) {
                repository.restoreRefundStatus(order.id(), order.status());
                return RefundPreparation.early(AdminRefundOrderResponse.warning(deduction.requireForceWarning(), true, order));
            }
            repository.insertAuditLog(order.id(), "REFUND_DEDUCTION_APPLIED", Map.of(
                    "type", deduction.type(),
                    "amount", deduction.balanceAmount(),
                    "subscriptionId", deduction.subscriptionId(),
                    "subscriptionDays", deduction.subscriptionDays(),
                    "subscriptionRevoked", deduction.subscriptionRevoked()
            ), "admin");
            return RefundPreparation.deduction(deduction);
        });
    }

    private RefundDeduction deductBalanceForRefund(PaymentOrderResponse order, double amount, boolean force) {
        Double balance = repository.findUserBalanceForUpdate(order.user_id()).orElse(null);
        if (balance == null) {
            if (!force) {
                return RefundDeduction.requiresForce(order.status(), "cannot fetch user balance, use force");
            }
            return RefundDeduction.none(order.status());
        }
        double deduction = Math.min(amount, Math.max(balance, 0.0d));
        if (deduction > 0 && !repository.hasAuditLog(order.id(), "REFUND_ROLLBACK_FAILED")) {
            repository.deductUserBalance(order.user_id(), deduction);
        }
        return RefundDeduction.balance(order.status(), deduction);
    }

    private RefundDeduction deductSubscriptionForRefund(PaymentOrderResponse order, boolean force) {
        Long groupId = repository.getSubscriptionGroupId(order.id()).orElse(null);
        Integer days = repository.getSubscriptionDays(order.id()).orElse(null);
        if (groupId == null || days == null || days <= 0) {
            return RefundDeduction.none(order.status());
        }
        AdminPaymentRepository.SubscriptionRefundTarget target = repository
                .findActiveSubscriptionForRefund(order.user_id(), groupId)
                .orElse(null);
        if (target == null) {
            if (!force) {
                return RefundDeduction.requiresForce(order.status(), "cannot find active subscription for deduction, use force");
            }
            return RefundDeduction.none(order.status());
        }
        boolean revoked = false;
        if (!repository.hasAuditLog(order.id(), "REFUND_ROLLBACK_FAILED")) {
            revoked = repository.deductSubscriptionDays(target.id(), days);
        }
        return RefundDeduction.subscription(order.status(), target.id(), days, revoked);
    }

    private void invokeProviderRefund(ProviderInstanceResponse provider, PaymentOrderResponse order, double gatewayAmount, String reason) {
        if (order.payment_trade_no() == null || order.payment_trade_no().isBlank()) {
            repository.insertAuditLog(order.id(), "REFUND_NO_TRADE_NO", Map.of("detail", "skipped"), "admin");
            return;
        }
        String providerKey = provider.provider_key() == null ? "" : provider.provider_key().trim().toLowerCase(Locale.ROOT);
        if ("stripe".equals(providerKey)) {
            stripePaymentClient.refund(provider, order, gatewayAmount, reason);
        } else if ("alipay".equals(providerKey)) {
            alipayPaymentClient.refund(provider, order, gatewayAmount, reason);
        } else if ("wxpay".equals(providerKey)) {
            wxpayPaymentClient.refund(provider, order, gatewayAmount, reason);
        } else if ("easypay".equals(providerKey)) {
            easyPayPaymentClient.refund(provider, order, gatewayAmount, reason);
        } else {
            throw new StructuredApiErrorException(400, "REFUND_PROVIDER_UNSUPPORTED", "payment provider does not support refund");
        }
    }

    private ProviderInstanceResponse resolveRefundProvider(PaymentOrderResponse order, Map<String, Object> snapshot) {
        String snapshotInstanceId = snapshotString(snapshot, "provider_instance_id");
        String orderInstanceId = trimToNull(order.provider_instance_id());
        if (snapshotInstanceId != null && orderInstanceId != null && !snapshotInstanceId.equalsIgnoreCase(orderInstanceId)) {
            repository.insertAuditLog(order.id(), "REFUND_PROVIDER_METADATA_MISMATCH", Map.of("detail", "provider_instance_id mismatch"), "admin");
            throw new StructuredApiErrorException(400, "REFUND_PROVIDER_METADATA_MISMATCH", "provider_instance_id mismatch");
        }
        String instanceId = snapshotInstanceId == null ? orderInstanceId : snapshotInstanceId;
        if (instanceId == null) {
            throw new StructuredApiErrorException(403, "REFUND_DISABLED", "refund is not available for this order");
        }
        return repository.getProviderByInstanceId(instanceId)
                .orElseThrow(() -> new StructuredApiErrorException(403, "REFUND_DISABLED", "refund is not available for this order"));
    }

    private void validateRefundProviderSnapshot(PaymentOrderResponse order, ProviderInstanceResponse provider, Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        try {
            assertSnapshotMatches(snapshot, "provider_instance_id", order.provider_instance_id(), String.valueOf(provider.id()));
            assertSnapshotMatches(snapshot, "provider_key", provider.provider_key());
            String providerKey = provider.provider_key() == null ? "" : provider.provider_key().trim().toLowerCase(Locale.ROOT);
            if ("easypay".equals(providerKey)) {
                assertSnapshotMatches(snapshot, "merchant_id", provider.config().get("pid"));
            } else if ("alipay".equals(providerKey)) {
                assertSnapshotMatches(snapshot, "merchant_app_id", provider.config().get("appId"));
            } else if ("wxpay".equals(providerKey)) {
                assertSnapshotMatchesAny(snapshot, "merchant_app_id", provider.config().get("appId"), provider.config().get("mpAppId"));
                assertSnapshotMatches(snapshot, "merchant_id", provider.config().get("mchId"));
            }
        } catch (StructuredApiErrorException ex) {
            repository.insertAuditLog(order.id(), "REFUND_PROVIDER_METADATA_MISMATCH", Map.of("detail", ex.getMessage()), "admin");
            throw ex;
        }
    }

    private void assertSnapshotMatches(Map<String, Object> snapshot, String snapshotKey, String... actualCandidates) {
        String expected = snapshotString(snapshot, snapshotKey);
        if (expected == null) {
            return;
        }
        for (String actual : actualCandidates) {
            String normalizedActual = trimToNull(actual);
            if (normalizedActual != null && expected.equalsIgnoreCase(normalizedActual)) {
                return;
            }
        }
        throw new StructuredApiErrorException(400, "REFUND_PROVIDER_METADATA_MISMATCH", snapshotKey + " mismatch");
    }

    private void assertSnapshotMatchesAny(Map<String, Object> snapshot, String snapshotKey, String... actualCandidates) {
        assertSnapshotMatches(snapshot, snapshotKey, actualCandidates);
    }

    private String snapshotString(Map<String, Object> snapshot, String key) {
        if (snapshot == null || !snapshot.containsKey(key)) {
            return null;
        }
        Object value = snapshot.get(key);
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private AdminRefundOrderResponse handleRefundGatewayFailure(PaymentOrderResponse order, RefundDeduction deduction, RuntimeException gatewayError) {
        boolean rolledBack = transactionTemplate.execute(status -> rollbackRefundDeduction(order, deduction, gatewayError));
        if (rolledBack) {
            repository.restoreRefundStatus(order.id(), deduction.previousStatus());
            repository.insertAuditLog(order.id(), "REFUND_GATEWAY_FAILED", Map.of(
                    "detail", safeMessage(gatewayError)
            ), "admin");
            PaymentOrderResponse restored = repository.getOrder(order.id()).orElse(order);
            return AdminRefundOrderResponse.warning("gateway failed: " + safeMessage(gatewayError) + ", rolled back", false, restored);
        }
        repository.markRefundFailed(order.id(), safeMessage(gatewayError));
        repository.insertAuditLog(order.id(), "REFUND_FAILED", Map.of(
                "detail", safeMessage(gatewayError)
        ), "admin");
        throw new StructuredApiErrorException(500, "REFUND_FAILED", safeMessage(gatewayError));
    }

    private boolean rollbackRefundDeduction(PaymentOrderResponse order, RefundDeduction deduction, RuntimeException gatewayError) {
        try {
            if ("balance".equals(deduction.type()) && deduction.balanceAmount() > 0) {
                repository.addUserBalance(order.user_id(), deduction.balanceAmount());
            }
            if ("subscription".equals(deduction.type()) && deduction.subscriptionId() > 0 && deduction.subscriptionDays() > 0) {
                if (deduction.subscriptionRevoked()) {
                    repository.restoreRevokedSubscription(deduction.subscriptionId());
                } else {
                    repository.restoreSubscriptionDays(deduction.subscriptionId(), deduction.subscriptionDays());
                }
            }
            return true;
        } catch (RuntimeException rollbackError) {
            repository.insertAuditLog(order.id(), "REFUND_ROLLBACK_FAILED", Map.of(
                    "gatewayError", safeMessage(gatewayError),
                    "rollbackError", safeMessage(rollbackError),
                    "type", deduction.type(),
                    "balanceDeducted", deduction.balanceAmount(),
                    "subDaysDeducted", deduction.subscriptionDays()
            ), "admin");
            return false;
        }
    }

    private double roundAmount(double amount) {
        return Math.round(amount * 100.0d) / 100.0d;
    }

    private String normalizeRefundReason(String requestedReason, PaymentOrderResponse order) {
        String reason = trimToNull(requestedReason);
        if (reason != null) {
            return reason;
        }
        reason = trimToNull(order.refund_request_reason());
        if (reason != null) {
            return reason;
        }
        return "refund order:" + order.id();
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private String normalizeValidityUnit(String value, boolean patch) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "day", "days" -> "days";
            case "week", "weeks" -> "weeks";
            case "month", "months" -> "months";
            default -> {
                if (patch) {
                    throw new IllegalArgumentException("validity_unit must be days, weeks, or months");
                }
                throw new IllegalArgumentException("validity_unit must be days, weeks, or months");
            }
        };
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return trimmed;
    }

    private Long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return value;
    }

    private Integer requirePositive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return value;
    }

    private Double requirePositive(Double value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return value;
    }

    private Integer normalizePositiveInt(Integer value) {
        if (value == null) {
            return null;
        }
        return value > 0 ? value : null;
    }

    private Integer normalizeNonNegativeInt(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(value, 0);
    }

    private record RefundPreparation(
            RefundDeduction deduction,
            AdminRefundOrderResponse earlyResponse
    ) {
        static RefundPreparation deduction(RefundDeduction deduction) {
            return new RefundPreparation(deduction, null);
        }

        static RefundPreparation early(AdminRefundOrderResponse response) {
            return new RefundPreparation(null, response);
        }
    }

    private record RefundDeduction(
            String previousStatus,
            String type,
            double balanceAmount,
            long subscriptionId,
            int subscriptionDays,
            boolean subscriptionRevoked,
            String requireForceWarning
    ) {
        static RefundDeduction none(String previousStatus) {
            return new RefundDeduction(previousStatus, "none", 0.0d, 0L, 0, false, null);
        }

        static RefundDeduction balance(String previousStatus, double amount) {
            return new RefundDeduction(previousStatus, "balance", amount, 0L, 0, false, null);
        }

        static RefundDeduction subscription(String previousStatus, long subscriptionId, int days, boolean revoked) {
            return new RefundDeduction(previousStatus, "subscription", 0.0d, subscriptionId, days, revoked, null);
        }

        static RefundDeduction requiresForce(String previousStatus, String warning) {
            return new RefundDeduction(previousStatus, "none", 0.0d, 0L, 0, false, warning);
        }
    }
}
