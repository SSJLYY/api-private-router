package org.apiprivaterouter.javabackend.admin.announcement.model;

import java.util.Map;

public record AnnouncementResponse(
        long id,
        String title,
        String content,
        String status,
        String notify_mode,
        Map<String, Object> targeting,
        String starts_at,
        String ends_at,
        Long created_by,
        Long updated_by,
        String created_at,
        String updated_at
) {
}
