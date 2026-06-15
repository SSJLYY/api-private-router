package org.apiprivaterouter.javabackend.userfund.model;

public record ApiDeductRequest(
        Long amount,
        String ref_type,
        Long ref_id,
        String description
) {}
