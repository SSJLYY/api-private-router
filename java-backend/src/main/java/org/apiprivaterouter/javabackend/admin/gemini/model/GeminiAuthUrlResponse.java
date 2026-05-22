package org.apiprivaterouter.javabackend.admin.gemini.model;

public record GeminiAuthUrlResponse(
        String auth_url,
        String session_id,
        String state
) {
}
