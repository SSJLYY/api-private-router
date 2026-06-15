package org.apiprivaterouter.javabackend.userfund.model;

public record AdminTransferRequest(
        Long from_user_id,
        Long to_user_id,
        Double amount,
        String remark
) {}
