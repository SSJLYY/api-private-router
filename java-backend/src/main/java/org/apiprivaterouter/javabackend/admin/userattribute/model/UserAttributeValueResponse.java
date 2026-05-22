package org.apiprivaterouter.javabackend.admin.userattribute.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserAttributeValueResponse(
        long id,
        @JsonProperty("user_id") long userId,
        @JsonProperty("attribute_id") long attributeId,
        String value,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
}
