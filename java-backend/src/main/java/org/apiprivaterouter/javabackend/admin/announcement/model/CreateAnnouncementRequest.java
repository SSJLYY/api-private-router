package org.apiprivaterouter.javabackend.admin.announcement.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAnnouncementRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String content,
        String status,
        String notify_mode,
        AnnouncementTargetingRequest targeting,
        Long starts_at,
        Long ends_at
) {
}
