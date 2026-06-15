package org.apiprivaterouter.javabackend.userfund.model;

public record RechargeRequest(
        Double amount,
        String channel,
        String external_order_id,
        String remark
) {}
