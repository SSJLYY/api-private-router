package org.apiprivaterouter.javabackend.admin.userattribute.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record UserAttributeDefinitionResponse(
        long id,
        String key,
        String name,
        String description,
        String type,
        List<UserAttributeOption> options,
        boolean required,
        UserAttributeValidation validation,
        String placeholder,
        @JsonProperty("display_order") int displayOrder,
        boolean enabled,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
}
