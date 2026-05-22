package org.apiprivaterouter.javabackend.admin.tlsfingerprint.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.CreateTLSFingerprintProfileRequest;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.TLSFingerprintProfileResponse;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.UpdateTLSFingerprintProfileRequest;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.service.AdminTLSFingerprintProfileService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/tls-fingerprint-profiles")
public class AdminTLSFingerprintProfileController {

    private final AdminTLSFingerprintProfileService service;
    private final CurrentUserContext currentUserContext;

    public AdminTLSFingerprintProfileController(AdminTLSFingerprintProfileService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<List<TLSFingerprintProfileResponse>> list() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<TLSFingerprintProfileResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getById(id));
    }

    @PostMapping
    public ApiResponse<TLSFingerprintProfileResponse> create(@Valid @RequestBody CreateTLSFingerprintProfileRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TLSFingerprintProfileResponse> update(
            @PathVariable long id,
            @RequestBody UpdateTLSFingerprintProfileRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        service.delete(id);
        return ApiResponse.success(Map.of("message", "Profile deleted successfully"));
    }
}
