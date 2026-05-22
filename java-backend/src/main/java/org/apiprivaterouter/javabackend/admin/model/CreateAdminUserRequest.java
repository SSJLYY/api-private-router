package org.apiprivaterouter.javabackend.admin.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateAdminUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6) String password,
        String username,
        Double balance,
        Integer concurrency,
        Integer rpm_limit,
        String notes,
        String status,
        List<Long> allowed_groups
) {
}
