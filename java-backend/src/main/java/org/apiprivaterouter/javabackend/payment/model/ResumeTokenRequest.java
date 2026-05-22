package org.apiprivaterouter.javabackend.payment.model;

import jakarta.validation.constraints.NotBlank;

public record ResumeTokenRequest(
        @NotBlank String resume_token
) {
}
