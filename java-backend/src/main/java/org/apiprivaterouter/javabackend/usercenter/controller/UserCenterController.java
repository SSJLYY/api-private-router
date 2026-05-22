package org.apiprivaterouter.javabackend.usercenter.controller;

import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.service.CurrentUserService;
import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.usercenter.model.ChangePasswordRequest;
import org.apiprivaterouter.javabackend.usercenter.model.UpdateProfileRequest;
import org.apiprivaterouter.javabackend.usercenter.service.UserCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UserCenterController {

    private final UserCenterService userCenterService;
    private final CurrentUserContext currentUserContext;
    private final CurrentUserService currentUserService;

    public UserCenterController(
            UserCenterService userCenterService,
            CurrentUserContext currentUserContext,
            CurrentUserService currentUserService
    ) {
        this.userCenterService = userCenterService;
        this.currentUserContext = currentUserContext;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/user/profile")
    public ApiResponse<CurrentUserResponse> getProfile() {
        return ApiResponse.success(currentUserService.getCurrentUser(currentUserContext.requireUser()));
    }

    @GetMapping("/users/me")
    public ApiResponse<CurrentUserResponse> getProfileAlias() {
        return ApiResponse.success(currentUserService.getCurrentUser(currentUserContext.requireUser()));
    }

    @PutMapping("/user")
    public ApiResponse<CurrentUserResponse> updateProfile(@RequestBody UpdateProfileRequest request) {
        userCenterService.updateProfile(currentUserContext.requireUser(), request);
        return ApiResponse.success(currentUserService.getCurrentUser(currentUserContext.requireUser()));
    }

    @PutMapping("/users/me")
    public ApiResponse<CurrentUserResponse> updateProfileAlias(@RequestBody UpdateProfileRequest request) {
        userCenterService.updateProfile(currentUserContext.requireUser(), request);
        return ApiResponse.success(currentUserService.getCurrentUser(currentUserContext.requireUser()));
    }

    @PutMapping("/user/password")
    public ApiResponse<Map<String, String>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponse.success(userCenterService.changePassword(currentUserContext.requireUser(), request));
    }

    @PostMapping("/users/me/password")
    public ApiResponse<Map<String, String>> changePasswordAlias(@Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponse.success(userCenterService.changePassword(currentUserContext.requireUser(), request));
    }

    @GetMapping("/user/aff")
    public ApiResponse<?> getAffiliateDetail() {
        return ApiResponse.success(userCenterService.getAffiliateDetail(currentUserContext.requireUser()));
    }

    @PostMapping("/user/aff/transfer")
    public ApiResponse<?> transferAffiliateQuota() {
        return ApiResponse.success(userCenterService.transferAffiliateQuota(currentUserContext.requireUser()));
    }
}
