package org.apiprivaterouter.javabackend.admin.userattribute.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.userattribute.model.BatchUserAttributesRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.BatchUserAttributesResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.model.CreateUserAttributeDefinitionRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.ReorderUserAttributeDefinitionsRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UpdateUserAttributeDefinitionRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UpdateUserAttributeValuesRequest;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeDefinitionResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.model.UserAttributeValueResponse;
import org.apiprivaterouter.javabackend.admin.userattribute.service.AdminUserAttributeService;
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
public class AdminUserAttributeController {

    private final AdminUserAttributeService service;
    private final CurrentUserContext currentUserContext;

    public AdminUserAttributeController(AdminUserAttributeService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/user-attributes")
    public ApiResponse<List<UserAttributeDefinitionResponse>> listDefinitions(
            @RequestParam(name = "enabled", defaultValue = "false") boolean enabled
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listDefinitions(enabled));
    }

    @PostMapping("/user-attributes")
    public ApiResponse<UserAttributeDefinitionResponse> createDefinition(
            @Valid @RequestBody CreateUserAttributeDefinitionRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createDefinition(request));
    }

    @PutMapping("/user-attributes/{id}")
    public ApiResponse<UserAttributeDefinitionResponse> updateDefinition(
            @PathVariable long id,
            @RequestBody UpdateUserAttributeDefinitionRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateDefinition(id, request));
    }

    @DeleteMapping("/user-attributes/{id}")
    public ApiResponse<Map<String, String>> deleteDefinition(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteDefinition(id));
    }

    @PutMapping("/user-attributes/reorder")
    public ApiResponse<Map<String, String>> reorderDefinitions(
            @Valid @RequestBody ReorderUserAttributeDefinitionsRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.reorderDefinitions(request.ids()));
    }

    @PostMapping("/user-attributes/batch")
    public ApiResponse<BatchUserAttributesResponse> batchUserAttributes(
            @Valid @RequestBody BatchUserAttributesRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchUserValues(request.userIds()));
    }

    @GetMapping("/users/{id}/attributes")
    public ApiResponse<List<UserAttributeValueResponse>> getUserValues(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUserValues(id));
    }

    @PutMapping("/users/{id}/attributes")
    public ApiResponse<List<UserAttributeValueResponse>> updateUserValues(
            @PathVariable long id,
            @Valid @RequestBody UpdateUserAttributeValuesRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateUserValues(id, request.values()));
    }
}
