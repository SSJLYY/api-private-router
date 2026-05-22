package org.apiprivaterouter.javabackend.usernotifyemail.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RemoveNotifyEmailRequest(
        @NotBlank @Email String email
) {
}
