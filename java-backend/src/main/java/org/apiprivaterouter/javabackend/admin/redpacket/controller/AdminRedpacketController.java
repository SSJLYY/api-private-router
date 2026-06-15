package org.apiprivaterouter.javabackend.admin.redpacket.controller;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.service.RedpacketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/redpackets")
public class AdminRedpacketController {

    private final RedpacketService redpacketService;
    private final CurrentUserContext currentUserContext;

    public AdminRedpacketController(
            RedpacketService redpacketService,
            CurrentUserContext currentUserContext
    ) {
        this.redpacketService = redpacketService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<RedpacketResponse>> listAll(
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(redpacketService.listAll(page, pageSize));
    }
}
