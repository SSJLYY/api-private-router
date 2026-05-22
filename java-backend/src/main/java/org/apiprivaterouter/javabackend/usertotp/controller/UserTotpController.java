package org.apiprivaterouter.javabackend.usertotp.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.usertotp.model.TotpDisableRequest;
import org.apiprivaterouter.javabackend.usertotp.model.TotpEnableRequest;
import org.apiprivaterouter.javabackend.usertotp.model.TotpSetupRequest;
import org.apiprivaterouter.javabackend.usertotp.model.TotpSetupResponse;
import org.apiprivaterouter.javabackend.usertotp.model.TotpStatusResponse;
import org.apiprivaterouter.javabackend.usertotp.model.TotpSuccessResponse;
import org.apiprivaterouter.javabackend.usertotp.model.TotpVerificationMethodResponse;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user/totp")
public class UserTotpController {

    private final UserTotpService userTotpService;
    private final CurrentUserContext currentUserContext;

    public UserTotpController(UserTotpService userTotpService, CurrentUserContext currentUserContext) {
        this.userTotpService = userTotpService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/status")
    public ApiResponse<TotpStatusResponse> getStatus() {
        CurrentUser user = currentUserContext.requireUser();
        return ApiResponse.success(userTotpService.getStatus(user));
    }

    @GetMapping("/verification-method")
    public ApiResponse<TotpVerificationMethodResponse> getVerificationMethod() {
        return ApiResponse.success(userTotpService.getVerificationMethod());
    }

    @PostMapping("/send-code")
    public ApiResponse<TotpSuccessResponse> sendVerifyCode() {
        CurrentUser user = currentUserContext.requireUser();
        userTotpService.sendVerifyCode(user);
        return ApiResponse.success(new TotpSuccessResponse(true));
    }

    @PostMapping("/setup")
    public ApiResponse<TotpSetupResponse> setup(@RequestBody(required = false) TotpSetupRequest request) {
        CurrentUser user = currentUserContext.requireUser();
        return ApiResponse.success(userTotpService.initiateSetup(user, request));
    }

    @PostMapping("/enable")
    public ApiResponse<TotpSuccessResponse> enable(@Valid @RequestBody TotpEnableRequest request) {
        CurrentUser user = currentUserContext.requireUser();
        userTotpService.completeSetup(user, request.totp_code(), request.setup_token());
        return ApiResponse.success(new TotpSuccessResponse(true));
    }

    @PostMapping("/disable")
    public ApiResponse<TotpSuccessResponse> disable(@RequestBody TotpDisableRequest request) {
        CurrentUser user = currentUserContext.requireUser();
        userTotpService.disable(user, request == null ? null : request.email_code(), request == null ? null : request.password());
        return ApiResponse.success(new TotpSuccessResponse(true));
    }
}
