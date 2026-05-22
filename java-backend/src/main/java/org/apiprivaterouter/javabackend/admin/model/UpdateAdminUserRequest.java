package org.apiprivaterouter.javabackend.admin.model;

import java.util.List;

public record UpdateAdminUserRequest(
        String email,
        String password,
        String username,
        String notes,
        Double balance,
        Integer concurrency,
        Integer rpm_limit,
        String status,
        List<Long> allowed_groups
) {
}
