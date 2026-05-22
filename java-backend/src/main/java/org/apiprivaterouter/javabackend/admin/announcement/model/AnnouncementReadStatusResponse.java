package org.apiprivaterouter.javabackend.admin.announcement.model;

public record AnnouncementReadStatusResponse(
        long user_id,
        String email,
        String username,
        double balance,
        boolean eligible,
        String read_at
) {
}
