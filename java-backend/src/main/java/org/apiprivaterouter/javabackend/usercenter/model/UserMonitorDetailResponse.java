package org.apiprivaterouter.javabackend.usercenter.model;

import java.util.List;

public record UserMonitorDetailResponse(
        long id,
        String name,
        String provider,
        String group_name,
        List<UserMonitorModelDetailResponse> models
) {
}
