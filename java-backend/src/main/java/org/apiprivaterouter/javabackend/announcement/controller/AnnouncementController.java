package org.apiprivaterouter.javabackend.announcement.controller;

import org.apiprivaterouter.javabackend.announcement.model.UserAnnouncementResponse;
import org.apiprivaterouter.javabackend.announcement.service.AnnouncementService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/announcements")
public class AnnouncementController {

    private final AnnouncementService service;
    private final CurrentUserContext currentUserContext;

    public AnnouncementController(AnnouncementService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<List<UserAnnouncementResponse>> list(@RequestParam(name = "unread_only", required = false) String unreadOnly) {
        long userId = currentUserContext.requireUser().userId();
        return ApiResponse.success(service.listForUser(userId, parseBool(unreadOnly)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, String>> markRead(@PathVariable long id) {
        long userId = currentUserContext.requireUser().userId();
        return ApiResponse.success(service.markRead(userId, id));
    }

    private boolean parseBool(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase()) {
            case "1", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }
}
