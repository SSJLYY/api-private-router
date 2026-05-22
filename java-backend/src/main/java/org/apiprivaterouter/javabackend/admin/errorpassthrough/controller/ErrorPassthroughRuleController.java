package org.apiprivaterouter.javabackend.admin.errorpassthrough.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.CreateErrorPassthroughRuleRequest;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.DeleteErrorPassthroughRuleResponse;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.ErrorPassthroughRuleResponse;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.UpdateErrorPassthroughRuleRequest;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.service.ErrorPassthroughRuleService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/error-passthrough-rules")
public class ErrorPassthroughRuleController {

    private final ErrorPassthroughRuleService service;
    private final CurrentUserContext currentUserContext;

    public ErrorPassthroughRuleController(ErrorPassthroughRuleService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<List<ErrorPassthroughRuleResponse>> list() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ErrorPassthroughRuleResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getById(id));
    }

    @PostMapping
    public ApiResponse<ErrorPassthroughRuleResponse> create(@Valid @RequestBody CreateErrorPassthroughRuleRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ErrorPassthroughRuleResponse> update(@PathVariable long id, @RequestBody UpdateErrorPassthroughRuleRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<DeleteErrorPassthroughRuleResponse> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        service.delete(id);
        return ApiResponse.success(new DeleteErrorPassthroughRuleResponse("Rule deleted successfully"));
    }
}
