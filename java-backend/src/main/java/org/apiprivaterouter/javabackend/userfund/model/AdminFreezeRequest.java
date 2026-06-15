package org.apiprivaterouter.javabackend.userfund.model;

public record AdminFreezeRequest(
        Long user_id,
        Double amount,
        String reason,
        String ref_type,
        Long ref_id
) {}
