package org.apiprivaterouter.javabackend.usercenter.model;

public record UserMonitorExtraModelResponse(
        String model,
        String status,
        Integer latency_ms
) {
}
