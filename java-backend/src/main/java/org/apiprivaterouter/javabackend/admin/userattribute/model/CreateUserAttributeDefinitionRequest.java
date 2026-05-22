package org.apiprivaterouter.javabackend.admin.userattribute.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateUserAttributeDefinitionRequest(
        @NotBlank @Size(max = 100) String key,
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotBlank String type,
        List<UserAttributeOption> options,
        Boolean required,
        UserAttributeValidation validation,
        String placeholder,
        Boolean enabled
) {
}
