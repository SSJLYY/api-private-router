package org.apiprivaterouter.javabackend.announcement.model;

public record UserAnnouncementResponse(
        long id,
        String title,
        String content,
        String notify_mode,
        String starts_at,
        String ends_at,
        String read_at,
        String created_at,
        String updated_at
) {
}
