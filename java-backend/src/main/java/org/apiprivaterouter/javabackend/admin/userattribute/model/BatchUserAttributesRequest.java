package org.apiprivaterouter.javabackend.admin.userattribute.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BatchUserAttributesRequest(
        @NotNull @JsonProperty("user_ids") List<Long> userIds
) {
}
