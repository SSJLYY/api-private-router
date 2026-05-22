package org.apiprivaterouter.javabackend.userredeem.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemHistoryItemResponse;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemRequest;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemResultResponse;
import org.apiprivaterouter.javabackend.userredeem.service.UserRedeemService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/redeem")
public class UserRedeemController {

    private final UserRedeemService userRedeemService;
    private final CurrentUserContext currentUserContext;

    public UserRedeemController(
            UserRedeemService userRedeemService,
            CurrentUserContext currentUserContext
    ) {
        this.userRedeemService = userRedeemService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/history")
    public ApiResponse<List<UserRedeemHistoryItemResponse>> history() {
        return ApiResponse.success(userRedeemService.getHistory(currentUserContext.requireUser()));
    }

    @PostMapping
    public ApiResponse<UserRedeemResultResponse> redeem(@RequestBody UserRedeemRequest request) {
        return ApiResponse.success(userRedeemService.redeem(currentUserContext.requireUser(), request));
    }
}
