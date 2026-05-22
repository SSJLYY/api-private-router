package org.apiprivaterouter.javabackend.admin.account.model;

public record GenerateAuthUrlResponse(
        String auth_url,
        String session_id
) {
}
