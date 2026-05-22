package org.apiprivaterouter.javabackend.admin.dashboard.model;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BatchUsersUsageRequest(@NotNull List<Long> user_ids) {
}
