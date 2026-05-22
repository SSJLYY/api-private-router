package org.apiprivaterouter.javabackend.usercenter.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String old_password,
        @NotBlank @Size(min = 6) String new_password
) {
}
