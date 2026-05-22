package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record UserBreakdownResponse(
        List<UserBreakdownItemResponse> users,
        String start_date,
        String end_date
) {
}
