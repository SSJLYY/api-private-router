package org.apiprivaterouter.javabackend.usernotifyemail.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ToggleNotifyEmailRequest(
        @NotBlank @Email String email,
        boolean disabled
) {
}
