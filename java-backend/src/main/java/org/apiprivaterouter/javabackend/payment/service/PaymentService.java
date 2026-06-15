package org.apiprivaterouter.javabackend.payment.service;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.auth.service.WeChatConnectConfigService;
import org.apiprivaterouter.javabackend.auth.service.WeChatPaymentOAuthService;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderResult;
import org.apiprivaterouter.javabackend.payment.model.PaymentChannelResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentConfigResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.RefundRequest;
import org.apiprivaterouter.javabackend.payment.model.ResumeTokenRequest;
import org.apiprivaterouter.javabackend.payment.model.SubscriptionPlanResponse;
import org.apiprivaterouter.javabackend.payment.model.VerifyOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.WechatOAuthInfo;
import org.apiprivaterouter.javabackend.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentResumeTokenService paymentResumeTokenService;
    private final WeChatPaymentOAuthService weChatPaymentOAuthService;
    private final WeChatConnectConfigService weChatConnectConfigService;
    private final EasyPayPaymentClient easyPayPaymentClient;
    private final AlipayPaymentClient alipayPaymentClient;
    private final WxpayPaymentClient wxpayPaymentClient;
    private final StripePaymentClient stripePaymentClient;
    private final PaymentWebhookService paymentWebhookService;
    private final JsonHelper jsonHelper;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentResumeTokenService paymentResumeTokenService,
            WeChatPaymentOAuthService weChatPaymentOAuthService,
            WeChatConnectConfigService weChatConnectConfigService,
            EasyPayPaymentClient easyPayPaymentClient,
            AlipayPaymentClient alipayPaymentClient,
            WxpayPaymentClient wxpayPaymentClient,
            StripePaymentClient stripePaymentClient,
            PaymentWebhookService paymentWebhookService,
            JsonHelper jsonHelper
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentResumeTokenService = paymentResumeTokenService;
        this.weChatPaymentOAuthService = weChatPaymentOAuthService;
        this.weChatConnectConfigService = weChatConnectConfigService;
        this.easyPayPaymentClient = easyPayPaymentClient;
        this.alipayPaymentClient = alipayPaymentClient;
        this.wxpayPaymentClient = wxpayPaymentClient;
        this.stripePaymentClient = stripePaymentClient;
        this.paymentWebhookService = paymentWebhookService;
        this.jsonHelper = jsonHelper;
    }

    public PaymentConfigResponse getConfig() {
        return paymentRepository.loadPaymentConfig();
    }

    public List<SubscriptionPlanResponse> getPlans() {
        return paymentRepository.loadPlansForSale();
    }

    public List<PaymentChannelResponse> getChannels() {
        return paymentRepository.loadEnabledChannels();
    }

    public List<ProviderInstanceResponse> getProviders() {
        return paymentRepository.loadProviderInstances();
    }

    public Map<String, Object> getCheckoutInfo() {
        PaymentConfigResponse config = getConfig();
        Map<String, Object> limits = getLimits();
        return Map.of(
                "methods", limits.get("methods"),
                "global_min", limits.get("global_min"),
                "global_max", limits.get("global_max"),
                "plans", getPlans(),
                "balance_disabled", config.balance_disabled(),
                "balance_recharge_multiplier", config.balance_recharge_multiplier(),
                "recharge_fee_rate", config.recharge_fee_rate(),
                "help_text", config.help_text(),
                "help_image_url", config.help_image_url(),
                "stripe_publishable_key", config.stripe_publishable_key()
        );
    }

    public Map<String, Object> getLimits() {
        return paymentRepository.loadLimitsSnapshot();
    }

    public CreateOrderResult createOrder(CurrentUser currentUser, CreateOrderRequest request, HttpServletRequest httpRequest) {
        String paymentType = normalizeVisibleMethod(request.payment_type());
        if (paymentType.isBlank()) {
            paymentType = request.payment_type() == null ? "" : request.payment_type().trim();
        }

        CreateOrderRequest effectiveRequest = request;
        String openid = normalizeOpenId(request.openid());
        if (request.wechat_resume_token() != null && !request.wechat_resume_token().trim().isEmpty()) {
            PaymentResumeTokenService.WeChatPaymentResumeClaims claims =
                    paymentResumeTokenService.parseWeChatPaymentResumeToken(request.wechat_resume_token().trim());
            String claimPaymentType = normalizeVisibleMethod(claims.paymentType());
            if (!paymentType.isBlank() && !claimPaymentType.isBlank() && !paymentType.equals(claimPaymentType)) {
                throw new StructuredApiErrorException(
                        400,
                        "INVALID_WECHAT_PAYMENT_RESUME_TOKEN",
                        "wechat payment resume token payment type mismatch"
                );
            }
            paymentType = claimPaymentType.isBlank() ? "wxpay" : claimPaymentType;
            openid = claims.openid();
            effectiveRequest = new CreateOrderRequest(
                    parsePositiveAmount(claims.amount(), request.amount()),
                    paymentType,
                    openid,
                    request.wechat_resume_token(),
                    request.return_url(),
                    request.payment_source(),
                    normalizeOrderType(claims.orderType(), request.order_type()),
                    claims.planId() != null && claims.planId() > 0 ? claims.planId() : request.plan_id(),
                    request.is_mobile()
            );
        } else {
            effectiveRequest = new CreateOrderRequest(
                    request.amount(),
                    paymentType,
                    openid,
                    null,
                    request.return_url(),
                    request.payment_source(),
                    normalizeOrderType(null, request.order_type()),
                    request.plan_id(),
                    request.is_mobile()
            );
        }

        PaymentConfigResponse config = getConfig();
        validatePaymentEnabled(config);
        SubscriptionPlanResponse plan = loadSelectedPlan(effectiveRequest);
        double limitAmount = resolveLimitAmount(effectiveRequest, plan);
        double orderAmount = resolveOrderAmount(effectiveRequest, plan, config);
        validateCreateOrderInput(effectiveRequest, config, plan, limitAmount);
        validateCreateOrderLimits(currentUser.userId(), config, limitAmount);
        ProviderInstanceResponse provider = resolveProvider(paymentType, calculatePayAmount(limitAmount, config, effectiveRequest.order_type()));
        String paymentMode = normalizePaymentMode(provider.payment_mode());

        if ("wxpay".equals(paymentType) && isWechatBrowser(httpRequest) && openid.isBlank() && "wxpay".equalsIgnoreCase(provider.provider_key())) {
            return buildWechatOAuthRequiredResult(effectiveRequest, paymentType, limitAmount, config, httpRequest);
        }

        effectiveRequest = new CreateOrderRequest(
                orderAmount,
                effectiveRequest.payment_type(),
                effectiveRequest.openid(),
                effectiveRequest.wechat_resume_token(),
                effectiveRequest.return_url(),
                effectiveRequest.payment_source(),
                effectiveRequest.order_type(),
                effectiveRequest.plan_id(),
                effectiveRequest.is_mobile()
        );
        String canonicalReturnUrl = canonicalizeReturnUrl(effectiveRequest.return_url());
        PaymentOrderResponse order = paymentRepository.createOrder(
                currentUser.userId(),
                currentUser.email(),
                effectiveRequest,
                paymentType,
                provider,
                initialPayUrl(paymentType, paymentMode),
                initialQrCode(paymentType, paymentMode, effectiveRequest.is_mobile()),
                clientIpFromRequest(httpRequest),
                hostFromRequest(httpRequest),
                canonicalReturnUrl,
                buildProviderSnapshot(provider, effectiveRequest),
                plan == null ? null : plan.group_id(),
                plan == null ? null : computeValidityDays(plan.validity_days(), plan.validity_unit()),
                orderAmount,
                limitAmount
        );

        String resumeToken = createOrderResumeToken(order);
        String payUrl = buildPayUrl(order, paymentMode, resumeToken);
        String qrCode = buildQrCode(order, paymentMode, effectiveRequest.is_mobile());
        String paymentTradeNo = null;
        String clientSecret = null;
        String resultType = "order_created";
        org.apiprivaterouter.javabackend.payment.model.WechatJsapiPayload jsapiPayload = null;
        try {
            if ("easypay".equalsIgnoreCase(provider.provider_key())) {
                EasyPayPaymentClient.EasyPayCreateOrderResult routed = easyPayPaymentClient.createOrder(
                        provider,
                        order,
                        effectiveRequest,
                        buildProviderNotifyUrl(provider, httpRequest),
                        buildProviderReturnUrl(canonicalReturnUrl, order, resumeToken),
                        clientIpFromRequest(httpRequest),
                        buildPaymentSubject(plan, limitAmount)
                );
                paymentMode = normalizePaymentMode(routed.paymentMode());
                payUrl = routed.payUrl();
                qrCode = routed.qrCode();
                paymentTradeNo = routed.tradeNo();
            } else if ("alipay".equalsIgnoreCase(provider.provider_key())) {
                AlipayPaymentClient.AlipayCreateOrderResult routed = alipayPaymentClient.createOrder(
                        provider,
                        order,
                        effectiveRequest,
                        buildProviderNotifyUrl(provider, httpRequest),
                        buildProviderReturnUrl(canonicalReturnUrl, order, resumeToken),
                        buildPaymentSubject(plan, limitAmount)
                );
                paymentTradeNo = firstNonBlank(routed.tradeNo(), paymentTradeNo);
                payUrl = firstNonBlank(routed.payUrl(), "");
                qrCode = routed.qrCode();
                paymentMode = normalizePaymentMode(firstNonBlank(routed.paymentMode(), paymentMode));
            } else if ("wxpay".equalsIgnoreCase(provider.provider_key())) {
                WxpayPaymentClient.WxpayCreateOrderResult routed = wxpayPaymentClient.createOrder(
                        provider,
                        order,
                        effectiveRequest,
                        buildProviderNotifyUrl(provider, httpRequest),
                        buildProviderReturnUrl(canonicalReturnUrl, order, resumeToken),
                        clientIpFromRequest(httpRequest),
                        buildPaymentSubject(plan, limitAmount)
                );
                paymentTradeNo = firstNonBlank(routed.paymentTradeNo(), paymentTradeNo);
                payUrl = firstNonBlank(routed.payUrl(), "");
                qrCode = routed.qrCode();
                paymentMode = normalizePaymentMode(firstNonBlank(routed.paymentMode(), paymentMode));
                resultType = firstNonBlank(routed.resultType(), resultType);
                jsapiPayload = routed.jsapiPayload();
            } else if ("stripe".equalsIgnoreCase(provider.provider_key())) {
                StripePaymentClient.StripeCreateOrderResult routed = stripePaymentClient.createOrder(
                        provider,
                        order,
                        effectiveRequest,
                        buildPaymentSubject(plan, limitAmount)
                );
                paymentTradeNo = routed.tradeNo();
                clientSecret = routed.clientSecret();
                payUrl = "";
                qrCode = null;
                paymentMode = "redirect";
            }
        } catch (RuntimeException ex) {
            paymentRepository.markOrderFailed(order.id(), ex.getMessage());
            throw ex;
        }
        paymentRepository.updatePaymentRouting(order.id(), payUrl, qrCode, paymentTradeNo);
        PaymentOrderResponse routedOrder = paymentRepository.findOrderByIdPublic(order.id()).orElse(order);

        return new CreateOrderResult(
                routedOrder.id(),
                routedOrder.amount(),
                routedOrder.pay_url(),
                routedOrder.qr_code(),
                clientSecret,
                routedOrder.pay_amount(),
                routedOrder.fee_rate(),
                routedOrder.expires_at(),
                resultType,
                routedOrder.payment_type(),
                routedOrder.out_trade_no(),
                paymentMode,
                resumeToken,
                null,
                jsapiPayload,
                jsapiPayload
        );
    }

    public PageResponse<PaymentOrderResponse> getMyOrders(CurrentUser currentUser, int page, int pageSize, String status, String orderType, String paymentType) {
        return paymentRepository.loadOrdersByUser(currentUser.userId(), page, pageSize, status, orderType, paymentType);
    }

    public PaymentOrderResponse getOrder(CurrentUser currentUser, long id) {
        PaymentOrderResponse order = paymentRepository.loadOrderByUserAndId(currentUser.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        return order;
    }

    public PaymentOrderResponse verifyOrder(CurrentUser currentUser, VerifyOrderRequest request) {
        PaymentOrderResponse order = paymentRepository.findOrderByOutTradeNo(currentUser.userId(), request.out_trade_no())
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        return reconcileOrder(order);
    }

    @Transactional
    public PaymentOrderResponse cancelOrder(CurrentUser currentUser, long id) {
        PaymentOrderResponse order = paymentRepository.loadOrderByUserAndId(currentUser.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        order = reconcileOrder(order);
        if (!"PENDING".equalsIgnoreCase(order.status())) {
            throw new StructuredApiErrorException(400, "INVALID_STATUS", "order cannot be cancelled in current status");
        }
        try {
            if ("stripe".equalsIgnoreCase(order.provider_key()) || "stripe".equalsIgnoreCase(order.payment_type())) {
                stripePaymentClient.cancelPayment(resolveOrderProvider(order), order);
            } else if ("alipay".equalsIgnoreCase(order.provider_key()) || "alipay".equalsIgnoreCase(order.payment_type())) {
                alipayPaymentClient.cancelPayment(resolveOrderProvider(order), order);
            } else if ("wxpay".equalsIgnoreCase(order.provider_key()) || "wxpay".equalsIgnoreCase(order.payment_type())) {
                wxpayPaymentClient.cancelPayment(resolveOrderProvider(order), order);
            }
        } catch (Exception ex) {
            log.warn("gateway cancel failed for order {}: {}", order.out_trade_no(), ex.getMessage());
        }
        PaymentOrderResponse cancelled = paymentRepository.cancelPendingOrder(currentUser.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        paymentRepository.insertAuditLog(id, "ORDER_CANCELLED", Map.of("detail", "user cancelled order"), "user:" + currentUser.userId());
        return cancelled;
    }

    @Transactional
    public PaymentOrderResponse requestRefund(CurrentUser currentUser, long id, RefundRequest request) {
        PaymentOrderResponse order = paymentRepository.loadOrderByUserAndId(currentUser.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        if (!"balance".equalsIgnoreCase(order.order_type())) {
            throw new StructuredApiErrorException(400, "INVALID_ORDER_TYPE", "only balance orders can request refund");
        }
        if (!"COMPLETED".equalsIgnoreCase(order.status())) {
            throw new StructuredApiErrorException(400, "INVALID_STATUS", "only completed orders can request refund");
        }
        ProviderInstanceResponse provider = resolveRefundProvider(order);
        if (!provider.allow_user_refund()) {
            throw new StructuredApiErrorException(403, "USER_REFUND_DISABLED", "user refund is not enabled for this provider");
        }
        double balance = paymentRepository.findUserBalance(currentUser.userId())
                .orElseThrow(() -> new StructuredApiErrorException(404, "NOT_FOUND", "user not found"));
        if (BigDecimal.valueOf(balance).compareTo(BigDecimal.valueOf(order.amount())) < 0) {
            throw new StructuredApiErrorException(400, "BALANCE_NOT_ENOUGH", "refund amount exceeds balance");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        PaymentOrderResponse updated = paymentRepository.markRefundRequested(currentUser.userId(), id, reason, order.amount())
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        if (!"REFUND_REQUESTED".equalsIgnoreCase(updated.status())) {
            throw new StructuredApiErrorException(409, "CONFLICT", "order status changed");
        }
        paymentRepository.insertAuditLog(id, "REFUND_REQUESTED", Map.of(
                "amount", order.amount(),
                "reason", reason
        ), "user:" + currentUser.userId());
        return updated;
    }

    public PaymentOrderResponse verifyOrderPublic(VerifyOrderRequest request) {
        return paymentRepository.findOrderByOutTradeNoPublic(request.out_trade_no())
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
    }

    public PaymentOrderResponse resolveOrderPublicByResumeToken(ResumeTokenRequest request) {
        PaymentResumeTokenService.ResumeTokenClaims claims = paymentResumeTokenService.parseToken(request.resume_token());
        PaymentOrderResponse order = paymentRepository.findOrderByIdPublic(claims.orderId())
                .orElseThrow(() -> new IllegalArgumentException("order not found"));
        if (claims.userId() != null && claims.userId() > 0 && order.user_id() != claims.userId()) {
            throw new StructuredApiErrorException(400, "INVALID_RESUME_TOKEN", "resume token does not match the payment order");
        }
        if (claims.providerInstanceId() != null && !claims.providerInstanceId().isBlank()
                && !claims.providerInstanceId().equals(order.provider_instance_id())) {
            throw new StructuredApiErrorException(400, "INVALID_RESUME_TOKEN", "resume token does not match the payment order");
        }
        if (claims.providerKey() != null && !claims.providerKey().isBlank()
                && !claims.providerKey().equalsIgnoreCase(order.provider_key())) {
            throw new StructuredApiErrorException(400, "INVALID_RESUME_TOKEN", "resume token does not match the payment order");
        }
        if (claims.paymentType() != null && !claims.paymentType().isBlank()
                && !normalizeVisibleMethod(order.payment_type()).equals(normalizeVisibleMethod(claims.paymentType()))) {
            throw new StructuredApiErrorException(400, "INVALID_RESUME_TOKEN", "resume token does not match the payment order");
        }
        return reconcileOrder(order);
    }

    public Map<String, List<String>> getRefundEligibleProviders() {
        return Map.of("provider_instance_ids", paymentRepository.getRefundEligibleProviderIds());
    }

    private void ensureWeChatPaymentOauthConfigured() {
        WeChatConnectConfigService.WeChatConnectConfig config = weChatConnectConfigService.getRequiredConfig();
        String appId = config.appIdForMode("mp");
        String secret = config.appSecretForMode("mp");
        if (!config.supportsMode("mp") || appId.isBlank() || secret.isBlank()) {
            throw new StructuredApiErrorException(
                    503,
                    "WECHAT_PAYMENT_MP_NOT_CONFIGURED",
                    "wechat in-app payment requires a complete WeChat MP OAuth credential"
            );
        }
    }

    private String createOrderResumeToken(PaymentOrderResponse order) {
        if (!paymentResumeTokenService.isSigningConfigured()) {
            return null;
        }
        return paymentResumeTokenService.createToken(new PaymentResumeTokenService.ResumeTokenClaims(
                order.id(),
                order.user_id(),
                order.provider_instance_id(),
                order.provider_key(),
                order.payment_type(),
                null,
                null,
                null
        ));
    }

    private String buildPayUrl(PaymentOrderResponse order, String paymentMode, String resumeToken) {
        if ("redirect".equals(paymentMode) || "popup".equals(paymentMode)) {
            String token = resumeToken == null ? "" : resumeToken.trim();
            String orderId = String.valueOf(order.id());
            return "/payment/result?order_id=" + orderId
                    + "&out_trade_no=" + safe(order.out_trade_no())
                    + "&resume_token=" + safe(token);
        }
        return "weixin://wxpay/bizpayurl?pr=" + safe(order.out_trade_no());
    }

    private String buildQrCode(PaymentOrderResponse order, String paymentMode, Boolean isMobile) {
        if ("redirect".equals(paymentMode) || "popup".equals(paymentMode)) {
            return Boolean.FALSE.equals(isMobile) ? "weixin://wxpay/bizpayurl?pr=" + safe(order.out_trade_no()) : null;
        }
        return "weixin://wxpay/bizpayurl?pr=" + safe(order.out_trade_no());
    }

    private String initialPayUrl(String paymentType, String paymentMode) {
        if ("redirect".equals(paymentMode) || "popup".equals(paymentMode)) {
            return "/payment/result";
        }
        if ("wxpay".equals(paymentType)) {
            return "weixin://wxpay/bizpayurl?pr=pending";
        }
        return "PAY://" + safe(paymentType) + "/pending";
    }

    private String initialQrCode(String paymentType, String paymentMode, Boolean isMobile) {
        if ("redirect".equals(paymentMode) || "popup".equals(paymentMode)) {
            return Boolean.FALSE.equals(isMobile) ? "weixin://wxpay/bizpayurl?pr=pending" : null;
        }
        if ("wxpay".equals(paymentType)) {
            return "weixin://wxpay/bizpayurl?pr=pending";
        }
        return "PAY://" + safe(paymentType) + "/pending";
    }

    private ProviderInstanceResponse resolveProvider(String paymentType) {
        return resolveProvider(paymentType, 0.0);
    }

    private ProviderInstanceResponse resolveProvider(String paymentType, double orderAmount) {
        return paymentRepository.resolveCreateProvider(paymentType, orderAmount);
    }

    private String normalizeVisibleMethod(String paymentType) {
        return PaymentResumeTokenService.normalizeVisibleMethod(paymentType);
    }

    private String normalizeOrderType(String fromClaims, String fromRequest) {
        String candidate = fromClaims != null && !fromClaims.trim().isEmpty() ? fromClaims.trim() : fromRequest;
        if (candidate == null || candidate.trim().isEmpty()) {
            return "balance";
        }
        return "subscription".equalsIgnoreCase(candidate.trim()) ? "subscription" : "balance";
    }

    private String normalizeOpenId(String openid) {
        return openid == null ? "" : openid.trim();
    }

    private double parsePositiveAmount(String rawAmount, Double fallback) {
        if (rawAmount == null || rawAmount.trim().isEmpty()) {
            return fallback == null ? 0.0 : fallback;
        }
        try {
            double parsed = Double.parseDouble(rawAmount.trim());
            if (parsed <= 0) {
                throw new StructuredApiErrorException(
                        400,
                        "INVALID_WECHAT_PAYMENT_RESUME_TOKEN",
                        "resume amount must be positive, got: " + rawAmount.trim()
                );
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            throw new StructuredApiErrorException(
                    400,
                    "INVALID_WECHAT_PAYMENT_RESUME_TOKEN",
                    "invalid resume amount: " + rawAmount.trim()
            );
        }
    }

    private boolean isWechatBrowser(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return userAgent != null && userAgent.toLowerCase(Locale.ROOT).contains("micromessenger");
    }

    private CreateOrderResult buildWechatOAuthRequiredResult(
            CreateOrderRequest request,
            String paymentType,
            double limitAmount,
            PaymentConfigResponse config,
            HttpServletRequest httpRequest
    ) {
        ensureWeChatPaymentOauthConfigured();
        if (!paymentResumeTokenService.isSigningConfigured()) {
            throw new StructuredApiErrorException(
                    503,
                    "PAYMENT_RESUME_NOT_CONFIGURED",
                    "payment resume tokens require a configured signing key"
            );
        }
        String redirect = paymentRedirectPathFromRequest(httpRequest);
        String authorizeUrl = weChatPaymentOAuthService.buildAuthorizeUrl(
                paymentType,
                redirect,
                limitAmount <= 0 ? null : stripTrailingZero(limitAmount),
                request.order_type(),
                request.plan_id(),
                "snsapi_base"
        );
        return new CreateOrderResult(
                0,
                limitAmount,
                null,
                null,
                null,
                calculatePayAmount(limitAmount, config, request.order_type()),
                feeRateForOrder(config, request.order_type()),
                null,
                "oauth_required",
                paymentType,
                null,
                "redirect",
                null,
                new WechatOAuthInfo(
                        authorizeUrl,
                        weChatConnectConfigService.getRequiredConfig().appIdForMode("mp"),
                        null,
                        "snsapi_base",
                        null,
                        "/auth/wechat/payment/callback"
                ),
                null,
                null
        );
    }

    private String paymentRedirectPathFromRequest(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.trim().isEmpty()) {
            return "/purchase";
        }
        try {
            URI uri = new URI(referer.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.isBlank() || !path.startsWith("/") || path.startsWith("//")) {
                return "/purchase";
            }
            if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                path = path + "?" + uri.getQuery();
            }
            return weChatPaymentOAuthService.normalizeRedirectPath(path);
        } catch (URISyntaxException ex) {
            return "/purchase";
        }
    }

    private String normalizePaymentMode(String paymentMode) {
        String raw = paymentMode == null ? "" : paymentMode.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            return "redirect";
        }
        return raw;
    }

    private SubscriptionPlanResponse loadSelectedPlan(CreateOrderRequest request) {
        if (!"subscription".equals(normalizeOrderType(null, request.order_type())) || request.plan_id() == null || request.plan_id() <= 0) {
            return null;
        }
        return paymentRepository.findPlanByIdForSale(request.plan_id())
                .orElseThrow(() -> new StructuredApiErrorException(404, "PLAN_NOT_AVAILABLE", "plan not found or not for sale"));
    }

    private double resolveLimitAmount(CreateOrderRequest request, SubscriptionPlanResponse plan) {
        if (plan != null) {
            return plan.price();
        }
        return request.amount() == null ? 0.0 : request.amount();
    }

    private double resolveOrderAmount(CreateOrderRequest request, SubscriptionPlanResponse plan, PaymentConfigResponse config) {
        if (plan != null) {
            return plan.price();
        }
        double amount = request.amount() == null ? 0.0 : request.amount();
        if ("balance".equals(normalizeOrderType(null, request.order_type()))) {
            BigDecimal multiplier = BigDecimal.valueOf(Math.max(config.balance_recharge_multiplier(), 0.0));
            return BigDecimal.valueOf(amount).multiply(multiplier)
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        return amount;
    }

    private void validatePaymentEnabled(PaymentConfigResponse config) {
        if (!config.payment_enabled()) {
            throw new StructuredApiErrorException(403, "PAYMENT_DISABLED", "payment system is disabled");
        }
    }

    private void validateCreateOrderInput(
            CreateOrderRequest request,
            PaymentConfigResponse config,
            SubscriptionPlanResponse plan,
            double limitAmount
    ) {
        String orderType = normalizeOrderType(null, request.order_type());
        if ("balance".equals(orderType) && config.balance_disabled()) {
            throw new StructuredApiErrorException(403, "BALANCE_PAYMENT_DISABLED", "balance recharge has been disabled");
        }
        if ("subscription".equals(orderType) && plan == null) {
            throw new StructuredApiErrorException(400, "INVALID_INPUT", "subscription order requires a plan");
        }
        BigDecimal bdAmount = BigDecimal.valueOf(limitAmount);
        if (Double.isNaN(limitAmount) || Double.isInfinite(limitAmount) || bdAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new StructuredApiErrorException(400, "INVALID_AMOUNT", "amount must be a positive number");
        }
        if ("balance".equals(orderType)
                && ((config.min_amount() > 0 && bdAmount.compareTo(BigDecimal.valueOf(config.min_amount())) < 0)
                || (config.max_amount() > 0 && bdAmount.compareTo(BigDecimal.valueOf(config.max_amount())) > 0))) {
            throw new StructuredApiErrorException(400, "INVALID_AMOUNT", "amount out of range");
        }
        Set<String> enabledTypes = normalizeEnabledPaymentTypes(config.enabled_payment_types());
        if (!enabledTypes.isEmpty() && !enabledTypes.contains(normalizeVisibleMethod(request.payment_type()))) {
            throw new StructuredApiErrorException(400, "PAYMENT_TYPE_DISABLED", "payment type is disabled");
        }
    }

    private void validateCreateOrderLimits(long userId, PaymentConfigResponse config, double limitAmount) {
        if (!paymentRepository.userIsActive(userId)) {
            throw new StructuredApiErrorException(403, "USER_INACTIVE", "user account is disabled");
        }
        int maxPending = config.max_pending_orders() <= 0 ? 3 : config.max_pending_orders();
        if (paymentRepository.countPendingOrders(userId) >= maxPending) {
            throw new StructuredApiErrorException(429, "TOO_MANY_PENDING", "too_many_pending");
        }
        if (config.daily_limit() > 0) {
            double paidToday = paymentRepository.sumUserPaidAmountToday(userId);
            if (BigDecimal.valueOf(paidToday).add(BigDecimal.valueOf(limitAmount))
                    .compareTo(BigDecimal.valueOf(config.daily_limit())) > 0) {
                throw new StructuredApiErrorException(429, "DAILY_LIMIT_EXCEEDED", "daily_limit_exceeded");
            }
        }
    }

    private Set<String> normalizeEnabledPaymentTypes(List<String> rawTypes) {
        Set<String> normalized = new HashSet<>();
        for (String raw : rawTypes == null ? List.<String>of() : rawTypes) {
            String method = normalizeVisibleMethod(raw);
            if (!method.isBlank() && !"easypay".equals(method)) {
                normalized.add(method);
            }
        }
        return normalized;
    }

    private double calculatePayAmount(double amount, PaymentConfigResponse config, String orderType) {
        BigDecimal base = BigDecimal.valueOf(amount);
        BigDecimal feeRate = BigDecimal.valueOf(feeRateForOrder(config, orderType));
        return base.multiply(BigDecimal.ONE.add(feeRate))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double roundAmount(double amount) {
        return BigDecimal.valueOf(amount)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double feeRateForOrder(PaymentConfigResponse config, String orderType) {
        return "balance".equals(normalizeOrderType(null, orderType)) ? config.recharge_fee_rate() : 0.0;
    }

    private int computeValidityDays(int days, String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "week", "weeks" -> days * 7;
            case "month", "months" -> days * 30;
            default -> days;
        };
    }

    private String buildPaymentSubject(SubscriptionPlanResponse plan, double payAmount) {
        if (plan != null) {
            if (plan.product_name() != null && !plan.product_name().isBlank()) {
                return plan.product_name().trim();
            }
            return "api-private-router Subscription " + plan.name();
        }
        return "api-private-router " + stripTrailingZero(payAmount) + " CNY";
    }

    private String buildProviderNotifyUrl(ProviderInstanceResponse provider, HttpServletRequest request) {
        String configured = firstNonBlank(
                provider.config().get("notifyUrl"),
                switch (normalizeProviderKey(provider.provider_key())) {
                    case "easypay" -> "/api/v1/payment/webhook/easypay";
                    case "alipay" -> "/api/v1/payment/webhook/alipay";
                    case "wxpay" -> "/api/v1/payment/webhook/wxpay";
                    case "stripe" -> "/api/v1/payment/webhook/stripe";
                    default -> "";
                }
        );
        if (configured.isBlank() || isAbsoluteHttpUrl(configured)) {
            return configured;
        }
        String origin = requestOrigin(request);
        if (origin.isBlank()) {
            return configured;
        }
        return origin + (configured.startsWith("/") ? configured : "/" + configured);
    }

    private String buildProviderReturnUrl(String canonicalReturnUrl, PaymentOrderResponse order, String resumeToken) {
        if (canonicalReturnUrl == null || canonicalReturnUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(canonicalReturnUrl);
            String queryPrefix = uri.getQuery() == null || uri.getQuery().isBlank() ? "" : uri.getQuery() + "&";
            String query = queryPrefix
                    + "order_id=" + urlEncode(String.valueOf(order.id()))
                    + "&out_trade_no=" + urlEncode(order.out_trade_no())
                    + "&status=success";
            if (resumeToken != null && !resumeToken.isBlank()) {
                query = query + "&resume_token=" + urlEncode(resumeToken);
            }
            URI resolved = new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    query,
                    null
            );
            return resolved.toString();
        } catch (Exception ex) {
            return canonicalReturnUrl;
        }
    }

    private String canonicalizeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.trim().isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(returnUrl.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new StructuredApiErrorException(400, "INVALID_RETURN_URL", "return_url must be an absolute http/https URL");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new StructuredApiErrorException(400, "INVALID_RETURN_URL", "return_url must use http or https");
            }
            if (!"/payment/result".equals(uri.getPath())) {
                throw new StructuredApiErrorException(400, "INVALID_RETURN_URL", "return_url must target the canonical internal payment result page");
            }
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toString();
        } catch (StructuredApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StructuredApiErrorException(400, "INVALID_RETURN_URL", "return_url must be a valid URL");
        }
    }

    private String buildProviderSnapshot(ProviderInstanceResponse provider, CreateOrderRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema_version", 2);
        if (provider.id() > 0) {
            snapshot.put("provider_instance_id", String.valueOf(provider.id()));
        }
        if (provider.provider_key() != null && !provider.provider_key().isBlank()) {
            snapshot.put("provider_key", provider.provider_key());
        }
        String paymentMode = normalizePaymentMode(provider.payment_mode());
        if (!paymentMode.isBlank()) {
            snapshot.put("payment_mode", paymentMode);
        }

        String providerKey = normalizeProviderKey(provider.provider_key());
        if ("easypay".equals(providerKey)) {
            putIfNotBlank(snapshot, "merchant_id", provider.config().get("pid"));
        } else if ("alipay".equals(providerKey)) {
            putIfNotBlank(snapshot, "appId", provider.config().get("appId"));
            putIfNotBlank(snapshot, "merchant_app_id", provider.config().get("appId"));
        } else if ("stripe".equals(providerKey)) {
            putIfNotBlank(snapshot, "publishable_key", provider.config().get("publishableKey"));
        } else if ("wxpay".equals(providerKey)) {
            String appId = request.openid() != null && !request.openid().isBlank()
                    ? firstNonBlank(provider.config().get("mpAppId"), provider.config().get("appId"))
                    : provider.config().get("appId");
            putIfNotBlank(snapshot, "appId", appId);
            putIfNotBlank(snapshot, "merchant_app_id", appId);
            putIfNotBlank(snapshot, "merchant_id", provider.config().get("mchId"));
            snapshot.put("currency", "CNY");
        }
        return snapshot.size() <= 1 ? "{}" : jsonHelper.writeJson(snapshot);
    }

    private String clientIpFromRequest(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return safe(request.getRemoteAddr());
    }

    private String hostFromRequest(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            return forwardedHost.split(",")[0].trim();
        }
        return safe(request.getHeader("Host"));
    }

    private String requestOrigin(HttpServletRequest request) {
        String scheme = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (scheme.isBlank()) {
            scheme = request.getScheme();
        }
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host.isBlank()) {
            host = firstHeaderValue(request.getHeader("Host"));
        }
        if (host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                host = host + ":" + port;
            }
        }
        if (scheme == null || scheme.isBlank() || host == null || host.isBlank()) {
            return "";
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + host;
    }

    private String firstHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.split(",")[0].trim();
    }

    private boolean isAbsoluteHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception ex) {
            return false;
        }
    }

    private String normalizeProviderKey(String providerKey) {
        return providerKey == null ? "" : providerKey.trim().toLowerCase(Locale.ROOT);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value.trim());
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String stripTrailingZero(Double amount) {
        if (amount == null) {
            return "";
        }
        if (amount == Math.rint(amount)) {
            return Long.toString(amount.longValue());
        }
        return amount.toString();
    }

    private String stripTrailingZero(double amount) {
        return stripTrailingZero(Double.valueOf(amount));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private PaymentOrderResponse reconcileOrder(PaymentOrderResponse order) {
        String providerKey = firstNonBlank(order.provider_key(), order.payment_type());
        if (!"stripe".equalsIgnoreCase(providerKey)
                && !"wxpay".equalsIgnoreCase(providerKey)
                && !"alipay".equalsIgnoreCase(providerKey)
                && !"easypay".equalsIgnoreCase(providerKey)) {
            return order;
        }
        if (!"PENDING".equalsIgnoreCase(order.status())
                && !"EXPIRED".equalsIgnoreCase(order.status())
                && !"CANCELLED".equalsIgnoreCase(order.status())) {
            return order;
        }
        ProviderInstanceResponse provider = resolveOrderProvider(order);
        if ("stripe".equalsIgnoreCase(provider.provider_key())) {
            StripePaymentClient.StripeQueryOrderResult upstream = stripePaymentClient.queryOrder(provider, order);
            if (!upstream.paid()) {
                return order;
            }
            paymentWebhookService.handleNotification(
                    new org.apiprivaterouter.javabackend.payment.model.PaymentWebhookNotification(
                            "stripe",
                            upstream.tradeNo(),
                            order.out_trade_no(),
                            upstream.amount(),
                            "success",
                            "",
                            Map.of()
                    ),
                    "stripe"
            );
        } else if ("alipay".equalsIgnoreCase(provider.provider_key())) {
            AlipayPaymentClient.AlipayQueryOrderResult upstream = alipayPaymentClient.queryOrder(provider, order);
            if (!upstream.paid()) {
                return order;
            }
            paymentWebhookService.handleNotification(
                    new org.apiprivaterouter.javabackend.payment.model.PaymentWebhookNotification(
                            "alipay",
                            upstream.tradeNo(),
                            order.out_trade_no(),
                            upstream.amount(),
                            "success",
                            "",
                            Map.of("app_id", firstNonBlank(provider.config().get("appId"), ""))
                    ),
                    "alipay"
            );
        } else if ("wxpay".equalsIgnoreCase(provider.provider_key())) {
            WxpayPaymentClient.WxpayQueryOrderResult upstream = wxpayPaymentClient.queryOrder(provider, order);
            if (!upstream.paid()) {
                return order;
            }
            paymentWebhookService.handleNotification(
                    new org.apiprivaterouter.javabackend.payment.model.PaymentWebhookNotification(
                            "wxpay",
                            upstream.tradeNo(),
                            order.out_trade_no(),
                            upstream.amount(),
                            "success",
                            "",
                            Map.of()
                    ),
                    "wxpay"
            );
        } else if ("easypay".equalsIgnoreCase(provider.provider_key())) {
            EasyPayPaymentClient.EasyPayQueryOrderResult upstream = easyPayPaymentClient.queryOrder(provider, order);
            if (!upstream.paid()) {
                return order;
            }
            paymentWebhookService.handleNotification(
                    new org.apiprivaterouter.javabackend.payment.model.PaymentWebhookNotification(
                            "easypay",
                            upstream.tradeNo(),
                            order.out_trade_no(),
                            upstream.amount(),
                            "success",
                            "",
                            Map.of("pid", firstNonBlank(provider.config().get("pid"), ""))
                    ),
                    "easypay"
            );
        }
        return paymentRepository.findOrderByIdPublic(order.id()).orElse(order);
    }

    public PaymentOrderResponse reconcileOrderForSystem(PaymentOrderResponse order) {
        return reconcileOrder(order);
    }

    private ProviderInstanceResponse resolveRefundProvider(PaymentOrderResponse order) {
        Map<String, Object> snapshot = paymentRepository.loadProviderSnapshot(order.id());
        String snapshotInstanceId = snapshotString(snapshot, "provider_instance_id");
        String orderInstanceId = blankToNull(order.provider_instance_id());
        if (snapshotInstanceId == null) {
            snapshotInstanceId = orderInstanceId;
        }
        if (snapshotInstanceId == null) {
            throw new StructuredApiErrorException(403, "USER_REFUND_DISABLED", "refund is not available for this order");
        }
        if (orderInstanceId != null
                && snapshot.containsKey("provider_instance_id")
                && !snapshotInstanceId.equalsIgnoreCase(orderInstanceId)) {
            throw new StructuredApiErrorException(403, "USER_REFUND_DISABLED", "refund is not available for this order");
        }
        ProviderInstanceResponse provider = paymentRepository.findProviderByInstanceId(snapshotInstanceId)
                .orElseThrow(() -> new StructuredApiErrorException(403, "USER_REFUND_DISABLED", "refund is not available for this order"));
        String snapshotProviderKey = snapshotString(snapshot, "provider_key");
        if (snapshotProviderKey != null && !snapshotProviderKey.equalsIgnoreCase(safe(provider.provider_key()))) {
            throw new StructuredApiErrorException(403, "USER_REFUND_DISABLED", "refund is not available for this order");
        }
        return provider;
    }

    private String snapshotString(Map<String, Object> snapshot, String key) {
        if (snapshot == null || key == null || !snapshot.containsKey(key)) {
            return null;
        }
        Object value = snapshot.get(key);
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ProviderInstanceResponse resolveOrderProvider(PaymentOrderResponse order) {
        String instanceId = order.provider_instance_id();
        if (instanceId != null && !instanceId.isBlank()) {
            return paymentRepository.findProviderByInstanceId(instanceId)
                    .orElseThrow(() -> new StructuredApiErrorException(500, "PAYMENT_PROVIDER_NOT_FOUND", "payment provider not found"));
        }
        return resolveProvider(order.payment_type());
    }
}
