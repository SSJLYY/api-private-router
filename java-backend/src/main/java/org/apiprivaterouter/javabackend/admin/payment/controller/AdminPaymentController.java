package org.apiprivaterouter.javabackend.admin.payment.controller;

import org.apiprivaterouter.javabackend.admin.payment.model.AdminPaymentConfigResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminPaymentDashboardResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminRefundOrderRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.AdminRefundOrderResponse;
import org.apiprivaterouter.javabackend.admin.payment.model.PlanUpsertRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.ProviderUpsertRequest;
import org.apiprivaterouter.javabackend.admin.payment.model.UpdateAdminPaymentConfigRequest;
import org.apiprivaterouter.javabackend.admin.payment.service.AdminPaymentService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.payment.model.PaymentChannelResponse;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.SubscriptionPlanResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/payment")
public class AdminPaymentController {

    private final AdminPaymentService service;
    private final CurrentUserContext currentUserContext;

    public AdminPaymentController(AdminPaymentService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/config")
    public ApiResponse<AdminPaymentConfigResponse> getConfig() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getConfig());
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, String>> updateConfig(@RequestBody UpdateAdminPaymentConfigRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateConfig(request));
    }

    @GetMapping("/dashboard")
    public ApiResponse<AdminPaymentDashboardResponse> getDashboard(@RequestParam(defaultValue = "30") int days) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getDashboard(days));
    }

    @GetMapping("/orders")
    public ApiResponse<PageResponse<PaymentOrderResponse>> listOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false, name = "user_id") Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "payment_type") String paymentType,
            @RequestParam(required = false, name = "order_type") String orderType,
            @RequestParam(required = false) String keyword
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listOrders(page, pageSize, userId, status, paymentType, orderType, keyword));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<Map<String, Object>> getOrder(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getOrderDetail(id));
    }

    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<PaymentOrderResponse> cancelOrder(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.cancelOrder(id));
    }

    @PostMapping("/orders/{id}/retry")
    public ApiResponse<PaymentOrderResponse> retryOrder(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.retryOrder(id));
    }

    @PostMapping("/orders/{id}/refund")
    public ApiResponse<AdminRefundOrderResponse> refundOrder(@PathVariable long id, @RequestBody AdminRefundOrderRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.refundOrder(id, request));
    }

    @GetMapping("/plans")
    public ApiResponse<List<SubscriptionPlanResponse>> getPlans() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listPlans());
    }

    @PostMapping("/plans")
    public ApiResponse<SubscriptionPlanResponse> createPlan(@RequestBody PlanUpsertRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createPlan(request));
    }

    @PutMapping("/plans/{id}")
    public ApiResponse<SubscriptionPlanResponse> updatePlan(@PathVariable long id, @RequestBody PlanUpsertRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updatePlan(id, request));
    }

    @DeleteMapping("/plans/{id}")
    public ApiResponse<Map<String, String>> deletePlan(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deletePlan(id));
    }

    @GetMapping("/providers")
    public ApiResponse<List<ProviderInstanceResponse>> getProviders() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listProviders());
    }

    @PostMapping("/providers")
    public ApiResponse<ProviderInstanceResponse> createProvider(@RequestBody ProviderUpsertRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createProvider(request));
    }

    @PutMapping("/providers/{id}")
    public ApiResponse<ProviderInstanceResponse> updateProvider(@PathVariable long id, @RequestBody ProviderUpsertRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateProvider(id, request));
    }

    @DeleteMapping("/providers/{id}")
    public ApiResponse<Map<String, String>> deleteProvider(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteProvider(id));
    }

    @GetMapping("/channels")
    public ApiResponse<List<PaymentChannelResponse>> getChannels() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listChannels());
    }

    @PostMapping("/channels")
    public ApiResponse<PaymentChannelResponse> createChannel(@RequestBody ProviderUpsertRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createChannel(request));
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<PaymentChannelResponse> updateChannel(@PathVariable long id, @RequestBody ProviderUpsertRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateChannel(id, request));
    }

    @DeleteMapping("/channels/{id}")
    public ApiResponse<Map<String, String>> deleteChannel(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteChannel(id));
    }
}
