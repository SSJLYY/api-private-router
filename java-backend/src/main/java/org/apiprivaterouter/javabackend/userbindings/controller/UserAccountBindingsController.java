package org.apiprivaterouter.javabackend.userbindings.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userbindings.model.BindEmailIdentityRequest;
import org.apiprivaterouter.javabackend.userbindings.model.SendEmailBindingCodeRequest;
import org.apiprivaterouter.javabackend.userbindings.model.StartIdentityBindingRequest;
import org.apiprivaterouter.javabackend.userbindings.model.StartIdentityBindingResponse;
import org.apiprivaterouter.javabackend.userbindings.service.UserAccountBindingsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
public class UserAccountBindingsController {

    private final CurrentUserContext currentUserContext;
    private final UserAccountBindingsService service;

    public UserAccountBindingsController(CurrentUserContext currentUserContext, UserAccountBindingsService service) {
        this.currentUserContext = currentUserContext;
        this.service = service;
    }

    @PostMapping("/auth-identities/bind/start")
    public ApiResponse<StartIdentityBindingResponse> startIdentityBinding(
            @Valid @RequestBody StartIdentityBindingRequest request
    ) {
        CurrentUser currentUser = currentUserContext.requireUser();
        return ApiResponse.success(service.startIdentityBinding(currentUser, request));
    }

    @DeleteMapping("/account-bindings/{provider}")
    public ApiResponse<CurrentUserResponse> unbindIdentity(@PathVariable String provider) {
        CurrentUser currentUser = currentUserContext.requireUser();
        return ApiResponse.success(service.unbindIdentity(currentUser, provider));
    }

    @PostMapping("/account-bindings/email/send-code")
    public ApiResponse<Map<String, String>> sendEmailBindingCode(
            @Valid @RequestBody SendEmailBindingCodeRequest request
    ) {
        CurrentUser currentUser = currentUserContext.requireUser();
        return ApiResponse.success(service.sendEmailBindingCode(currentUser, request));
    }

    @PostMapping("/account-bindings/email")
    public ApiResponse<CurrentUserResponse> bindEmailIdentity(
            @Valid @RequestBody BindEmailIdentityRequest request
    ) {
        CurrentUser currentUser = currentUserContext.requireUser();
        return ApiResponse.success(service.bindEmailIdentity(currentUser, request));
    }
}
