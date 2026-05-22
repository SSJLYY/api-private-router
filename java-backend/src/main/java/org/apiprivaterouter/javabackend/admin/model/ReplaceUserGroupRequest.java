package org.apiprivaterouter.javabackend.admin.model;

import jakarta.validation.constraints.Min;

public record ReplaceUserGroupRequest(
        @Min(1) long old_group_id,
        @Min(1) long new_group_id
) {
}
