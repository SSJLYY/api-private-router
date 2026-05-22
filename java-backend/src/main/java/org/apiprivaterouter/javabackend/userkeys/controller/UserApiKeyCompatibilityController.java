package org.apiprivaterouter.javabackend.userkeys.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userkeys.model.CreateUserApiKeyRequest;
import org.apiprivaterouter.javabackend.userkeys.model.UpdateUserApiKeyRequest;
import org.apiprivaterouter.javabackend.userkeys.model.UserApiKeyResponse;
import org.apiprivaterouter.javabackend.userkeys.service.UserApiKeyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/api-keys")
public class UserApiKeyCompatibilityController {

    private final UserApiKeyService service;
    private final CurrentUserContext currentUserContext;

    public UserApiKeyCompatibilityController(UserApiKeyService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserApiKeyResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        return ApiResponse.success(service.list(
                currentUserContext.requireUser(),
                page,
                pageSize,
                search,
                status,
                groupId,
                sortBy,
                sortOrder
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserApiKeyResponse> getById(@PathVariable long id) {
        return ApiResponse.success(service.getById(currentUserContext.requireUser(), id));
    }

    @PostMapping
    public ApiResponse<UserApiKeyResponse> create(@Valid @RequestBody CreateUserApiKeyRequest request) {
        return ApiResponse.success(service.create(currentUserContext.requireUser(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserApiKeyResponse> update(@PathVariable long id, @Valid @RequestBody UpdateUserApiKeyRequest request) {
        return ApiResponse.success(service.update(currentUserContext.requireUser(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        return ApiResponse.success(service.delete(currentUserContext.requireUser(), id));
    }
}
