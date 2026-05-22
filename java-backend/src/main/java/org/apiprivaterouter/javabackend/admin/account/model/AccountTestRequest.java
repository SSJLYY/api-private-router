package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountTestRequest(
        String model_id,
        String prompt,
        String mode
) {
}
