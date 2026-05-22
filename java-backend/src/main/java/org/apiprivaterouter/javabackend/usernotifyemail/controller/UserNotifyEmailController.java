package org.apiprivaterouter.javabackend.usernotifyemail.controller;

import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.service.CurrentUserService;
import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.usernotifyemail.model.RemoveNotifyEmailRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.model.SendNotifyEmailCodeRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.model.ToggleNotifyEmailRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.model.VerifyNotifyEmailRequest;
import org.apiprivaterouter.javabackend.usernotifyemail.service.UserNotifyEmailService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class UserNotifyEmailController {

    private final UserNotifyEmailService userNotifyEmailService;
    private final CurrentUserContext currentUserContext;
    private final CurrentUserService currentUserService;

    public UserNotifyEmailController(
            UserNotifyEmailService userNotifyEmailService,
            CurrentUserContext currentUserContext,
            CurrentUserService currentUserService
    ) {
        this.userNotifyEmailService = userNotifyEmailService;
        this.currentUserContext = currentUserContext;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/user/notify-email/send-code")
    public ApiResponse<Void> sendCode(@Valid @RequestBody SendNotifyEmailCodeRequest request) {
        CurrentUser currentUser = currentUserContext.requireUser();
        userNotifyEmailService.sendCode(currentUser, request);
        return ApiResponse.successMessage("Verification code sent successfully");
    }

    @PostMapping("/user/notify-email/verify")
    public ApiResponse<CurrentUserResponse> verify(@Valid @RequestBody VerifyNotifyEmailRequest request) {
        CurrentUser currentUser = currentUserContext.requireUser();
        userNotifyEmailService.verify(currentUser, request);
        return ApiResponse.success(currentUserService.getCurrentUser(currentUser));
    }

    @PutMapping("/user/notify-email/toggle")
    public ApiResponse<CurrentUserResponse> toggle(@Valid @RequestBody ToggleNotifyEmailRequest request) {
        CurrentUser currentUser = currentUserContext.requireUser();
        userNotifyEmailService.toggle(currentUser, request);
        return ApiResponse.success(currentUserService.getCurrentUser(currentUser));
    }

    @DeleteMapping("/user/notify-email")
    public ApiResponse<CurrentUserResponse> remove(@Valid @RequestBody RemoveNotifyEmailRequest request) {
        CurrentUser currentUser = currentUserContext.requireUser();
        userNotifyEmailService.remove(currentUser, request);
        return ApiResponse.success(currentUserService.getCurrentUser(currentUser));
    }
}
