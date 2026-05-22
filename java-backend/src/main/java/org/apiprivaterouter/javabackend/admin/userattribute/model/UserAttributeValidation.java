package org.apiprivaterouter.javabackend.admin.userattribute.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserAttributeValidation(
        @JsonProperty("min_length") Integer minLength,
        @JsonProperty("max_length") Integer maxLength,
        Integer min,
        Integer max,
        String pattern,
        String message
) {
}
