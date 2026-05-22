package org.apiprivaterouter.javabackend.usercheckin.controller;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinCalendarResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinHistoryItemResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinRequest;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinResultResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinStatusResponse;
import org.apiprivaterouter.javabackend.usercheckin.service.UserCheckinService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkin")
public class UserCheckinController {

    private final UserCheckinService userCheckinService;
    private final CurrentUserContext currentUserContext;

    public UserCheckinController(
            UserCheckinService userCheckinService,
            CurrentUserContext currentUserContext
    ) {
        this.userCheckinService = userCheckinService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/status")
    public ApiResponse<UserCheckinStatusResponse> status(
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(userCheckinService.getStatus(currentUserContext.requireUser(), timezone));
    }

    @PostMapping
    public ApiResponse<UserCheckinResultResponse> checkin(
            @RequestBody UserCheckinRequest request
    ) {
        return ApiResponse.success(userCheckinService.checkin(currentUserContext.requireUser(), request));
    }

    @GetMapping("/calendar")
    public ApiResponse<UserCheckinCalendarResponse> calendar(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(userCheckinService.getMonthCalendar(
                currentUserContext.requireUser(),
                year,
                month,
                timezone
        ));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<UserCheckinHistoryItemResponse>> history(
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(userCheckinService.getHistory(currentUserContext.requireUser(), page, pageSize));
    }
}
