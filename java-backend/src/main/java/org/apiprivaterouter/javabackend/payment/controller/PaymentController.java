package org.apiprivaterouter.javabackend.payment.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
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
import org.apiprivaterouter.javabackend.payment.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payment")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserContext currentUserContext;

    public PaymentController(PaymentService paymentService, CurrentUserContext currentUserContext) {
        this.paymentService = paymentService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/config")
    public ApiResponse<PaymentConfigResponse> getConfig() {
        currentUserContext.requireUser();
        return ApiResponse.success(paymentService.getConfig());
    }

    @GetMapping("/plans")
    public ApiResponse<List<SubscriptionPlanResponse>> getPlans() {
        currentUserContext.requireUser();
        return ApiResponse.success(paymentService.getPlans());
    }

    @GetMapping("/products")
    public ApiResponse<List<SubscriptionPlanResponse>> getProducts() {
        currentUserContext.requireUser();
        return ApiResponse.success(paymentService.getPlans());
    }

    @GetMapping("/channels")
    public ApiResponse<List<PaymentChannelResponse>> getChannels() {
        currentUserContext.requireUser();
        return ApiResponse.success(paymentService.getChannels());
    }

    @GetMapping("/providers")
    public ApiResponse<List<ProviderInstanceResponse>> getProviders() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(paymentService.getProviders());
    }

    @GetMapping("/checkout-info")
    public ApiResponse<Map<String, Object>> getCheckoutInfo() {
        currentUserContext.requireUser();
        return ApiResponse.success(paymentService.getCheckoutInfo());
    }

    @GetMapping("/limits")
    public ApiResponse<Map<String, Object>> getLimits() {
        currentUserContext.requireUser();
        return ApiResponse.success(paymentService.getLimits());
    }

    @PostMapping("/orders")
    public ApiResponse<CreateOrderResult> createOrder(@Valid @RequestBody CreateOrderRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(paymentService.createOrder(currentUserContext.requireUser(), request, httpRequest));
    }

    @GetMapping("/orders/my")
    public ApiResponse<PageResponse<PaymentOrderResponse>> getMyOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(name = "order_type", required = false) String orderType,
            @RequestParam(name = "payment_type", required = false) String paymentType
    ) {
        return ApiResponse.success(paymentService.getMyOrders(currentUserContext.requireUser(), page, pageSize, status, orderType, paymentType));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<PaymentOrderResponse> getOrder(@PathVariable long id) {
        return ApiResponse.success(paymentService.getOrder(currentUserContext.requireUser(), id));
    }

    @PostMapping("/orders/verify")
    public ApiResponse<PaymentOrderResponse> verifyOrder(@Valid @RequestBody VerifyOrderRequest request) {
        return ApiResponse.success(paymentService.verifyOrder(currentUserContext.requireUser(), request));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<PaymentOrderResponse> cancelOrder(@PathVariable long id) {
        return ApiResponse.success(paymentService.cancelOrder(currentUserContext.requireUser(), id));
    }

    @PostMapping("/orders/{id}/refund-request")
    public ApiResponse<PaymentOrderResponse> requestRefund(@PathVariable long id, @Valid @RequestBody RefundRequest request) {
        return ApiResponse.success(paymentService.requestRefund(currentUserContext.requireUser(), id, request));
    }

    @PostMapping("/public/orders/verify")
    public ApiResponse<PaymentOrderResponse> verifyOrderPublic(@Valid @RequestBody VerifyOrderRequest request) {
        return ApiResponse.success(paymentService.verifyOrderPublic(request));
    }

    @PostMapping("/public/orders/resolve")
    public ApiResponse<PaymentOrderResponse> resolveOrderPublicByResumeToken(@Valid @RequestBody ResumeTokenRequest request) {
        return ApiResponse.success(paymentService.resolveOrderPublicByResumeToken(request));
    }

    @GetMapping("/orders/refund-eligible-providers")
    public ApiResponse<Map<String, List<String>>> getRefundEligibleProviders() {
        return ApiResponse.success(paymentService.getRefundEligibleProviders());
    }
}
