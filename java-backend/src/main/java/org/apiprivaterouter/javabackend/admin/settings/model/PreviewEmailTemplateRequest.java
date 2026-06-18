package org.apiprivaterouter.javabackend.admin.settings.model;

import jakarta.validation.constraints.NotBlank;

public record PreviewEmailTemplateRequest(
        @NotBlank String event,
        @NotBlank String locale,
        String subject,
        String bodyHtml,
        String bodyText,
        @NotBlank String toEmail
) {
}
