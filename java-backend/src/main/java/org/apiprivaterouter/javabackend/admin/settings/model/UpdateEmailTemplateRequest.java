package org.apiprivaterouter.javabackend.admin.settings.model;

import jakarta.validation.constraints.NotBlank;

public record UpdateEmailTemplateRequest(
        String subject,
        String bodyHtml,
        String bodyText
) {
}
