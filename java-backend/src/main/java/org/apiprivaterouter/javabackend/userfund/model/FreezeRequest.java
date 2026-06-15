package org.apiprivaterouter.javabackend.userfund.model;

public record FreezeRequest(
        Double amount,
        String reason,
        String ref_type,
        Long ref_id
) {}
