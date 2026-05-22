package org.apiprivaterouter.javabackend.admin.antigravity.model;

public record AntigravityAuthUrlResponse(
        String auth_url,
        String session_id,
        String state
) {
}
