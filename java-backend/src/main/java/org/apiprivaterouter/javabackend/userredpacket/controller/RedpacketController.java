package org.apiprivaterouter.javabackend.userredpacket.controller;

import jakarta.validation.Valid;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userredpacket.model.ClaimRedpacketRequest;
import org.apiprivaterouter.javabackend.userredpacket.model.ClaimRedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.CreateRedpacketRequest;
import org.apiprivaterouter.javabackend.userredpacket.model.CreateRedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketDetailResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.service.RedpacketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/redpacket")
public class RedpacketController {

    private final RedpacketService redpacketService;
    private final CurrentUserContext currentUserContext;

    public RedpacketController(
            RedpacketService redpacketService,
            CurrentUserContext currentUserContext
    ) {
        this.redpacketService = redpacketService;
        this.currentUserContext = currentUserContext;
    }

    @PostMapping
    public ApiResponse<CreateRedpacketResponse> createRedpacket(
            @Valid @RequestBody CreateRedpacketRequest request
    ) {
        return ApiResponse.success(redpacketService.createRedpacket(currentUserContext.requireUser(), request));
    }

    @PostMapping("/claim")
    public ApiResponse<ClaimRedpacketResponse> claimRedpacket(
            @Valid @RequestBody ClaimRedpacketRequest request
    ) {
        return ApiResponse.success(redpacketService.claimRedpacket(currentUserContext.requireUser(), request));
    }

    @GetMapping("/{id}")
    public ApiResponse<RedpacketDetailResponse> getRedpacketDetail(
            @PathVariable long id
    ) {
        long userId = currentUserContext.requireUser().userId();
        return ApiResponse.success(redpacketService.getRedpacketDetail(id, userId));
    }

    @GetMapping("/my")
    public ApiResponse<PageResponse<RedpacketResponse>> getMyRedpackets(
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(redpacketService.listByCreator(currentUserContext.requireUser().userId(), page, pageSize));
    }
}
