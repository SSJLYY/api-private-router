package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

public record ContentModerationUnbanUserResponse(
        long user_id,
        String status
) {
}
