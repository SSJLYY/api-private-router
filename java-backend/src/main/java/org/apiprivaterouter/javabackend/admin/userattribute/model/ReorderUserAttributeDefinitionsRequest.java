package org.apiprivaterouter.javabackend.admin.userattribute.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderUserAttributeDefinitionsRequest(
        @NotEmpty List<Long> ids
) {
}
