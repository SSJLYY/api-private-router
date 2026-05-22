package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

public record DeleteFlaggedHashResponse(
        String input_hash,
        boolean deleted
) {
}
