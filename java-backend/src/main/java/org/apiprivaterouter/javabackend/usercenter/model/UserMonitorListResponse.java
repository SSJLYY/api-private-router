package org.apiprivaterouter.javabackend.usercenter.model;

import java.util.List;

public record UserMonitorListResponse(
        List<UserMonitorViewResponse> items
) {
}
