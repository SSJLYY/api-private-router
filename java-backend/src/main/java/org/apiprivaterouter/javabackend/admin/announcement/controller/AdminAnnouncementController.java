package org.apiprivaterouter.javabackend.admin.announcement.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.announcement.model.AnnouncementReadStatusResponse;
import org.apiprivaterouter.javabackend.admin.announcement.model.AnnouncementResponse;
import org.apiprivaterouter.javabackend.admin.announcement.model.CreateAnnouncementRequest;
import org.apiprivaterouter.javabackend.admin.announcement.model.UpdateAnnouncementRequest;
import org.apiprivaterouter.javabackend.admin.announcement.service.AdminAnnouncementService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
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

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/announcements")
public class AdminAnnouncementController {

    private final AdminAnnouncementService service;
    private final CurrentUserContext currentUserContext;

    public AdminAnnouncementController(AdminAnnouncementService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AnnouncementResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listAnnouncements(page, pageSize, status, search, sortBy, sortOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnnouncementResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAnnouncement(id));
    }

    @PostMapping
    public ApiResponse<AnnouncementResponse> create(@Valid @RequestBody CreateAnnouncementRequest request) {
        long actorId = currentUserContext.requireAdmin().userId();
        return ApiResponse.success(service.createAnnouncement(request, actorId));
    }

    @PutMapping("/{id}")
    public ApiResponse<AnnouncementResponse> update(@PathVariable long id, @RequestBody UpdateAnnouncementRequest request) {
        long actorId = currentUserContext.requireAdmin().userId();
        return ApiResponse.success(service.updateAnnouncement(id, request, actorId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteAnnouncement(id));
    }

    @GetMapping("/{id}/read-status")
    public ApiResponse<PageResponse<AnnouncementReadStatusResponse>> readStatus(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listReadStatus(id, page, pageSize, search, sortBy, sortOrder));
    }
}
