package org.apiprivaterouter.javabackend.admin.announcement.model;

public record UpdateAnnouncementRequest(
        String title,
        String content,
        String status,
        String notify_mode,
        AnnouncementTargetingRequest targeting,
        Long starts_at,
        Long ends_at
) {
}
