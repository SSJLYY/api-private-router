package org.apiprivaterouter.javabackend.admin.userattribute.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record UpdateUserAttributeValuesRequest(
        @NotNull @JsonProperty("values") Map<Long, String> values
) {
}
