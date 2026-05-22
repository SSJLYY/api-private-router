package org.apiprivaterouter.javabackend.admin.account.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.account.model.CreateScheduledTestPlanRequest;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestPlanResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.UpdateScheduledTestPlanRequest;
import org.apiprivaterouter.javabackend.admin.account.service.ScheduledTestPlanService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class ScheduledTestPlanController {

    private final ScheduledTestPlanService service;
    private final CurrentUserContext currentUserContext;

    public ScheduledTestPlanController(ScheduledTestPlanService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/accounts/{id}/scheduled-test-plans")
    public ApiResponse<List<ScheduledTestPlanResponse>> listByAccount(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listByAccount(id));
    }

    @PostMapping("/scheduled-test-plans")
    public ApiResponse<ScheduledTestPlanResponse> create(@Valid @RequestBody CreateScheduledTestPlanRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createPlan(request));
    }

    @PutMapping("/scheduled-test-plans/{id}")
    public ApiResponse<ScheduledTestPlanResponse> update(@PathVariable long id, @RequestBody UpdateScheduledTestPlanRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updatePlan(id, request));
    }

    @DeleteMapping("/scheduled-test-plans/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        service.deletePlan(id);
        return ApiResponse.success(Map.of("message", "deleted"));
    }

    @GetMapping("/scheduled-test-plans/{id}/results")
    public ApiResponse<List<ScheduledTestResultResponse>> listResults(
            @PathVariable long id,
            @RequestParam(required = false) Integer limit
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listResults(id, limit));
    }
}
