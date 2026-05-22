package org.apiprivaterouter.javabackend.usercenter.controller;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.admin.subscription.model.SubscriptionProgressResponse;
import org.apiprivaterouter.javabackend.usercenter.model.UserSubscriptionProgressItemResponse;
import org.apiprivaterouter.javabackend.usercenter.model.UserSubscriptionResponse;
import org.apiprivaterouter.javabackend.usercenter.model.UserSubscriptionSummaryResponse;
import org.apiprivaterouter.javabackend.usercenter.service.UserSubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class UserSubscriptionController {

    private final UserSubscriptionService userSubscriptionService;
    private final CurrentUserContext currentUserContext;

    public UserSubscriptionController(
            UserSubscriptionService userSubscriptionService,
            CurrentUserContext currentUserContext
    ) {
        this.userSubscriptionService = userSubscriptionService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<List<UserSubscriptionResponse>> list() {
        return ApiResponse.success(userSubscriptionService.list(currentUserContext.requireUser()));
    }

    @GetMapping("/active")
    public ApiResponse<List<UserSubscriptionResponse>> listActive() {
        return ApiResponse.success(userSubscriptionService.listActive(currentUserContext.requireUser()));
    }

    @GetMapping("/progress")
    public ApiResponse<List<UserSubscriptionProgressItemResponse>> listProgress() {
        return ApiResponse.success(userSubscriptionService.listProgress(currentUserContext.requireUser()));
    }

    @GetMapping("/{id}/progress")
    public ApiResponse<SubscriptionProgressResponse> getProgress(@PathVariable long id) {
        return ApiResponse.success(userSubscriptionService.getProgress(currentUserContext.requireUser(), id));
    }

    @GetMapping("/summary")
    public ApiResponse<UserSubscriptionSummaryResponse> summary() {
        return ApiResponse.success(userSubscriptionService.getSummary(currentUserContext.requireUser()));
    }
}
