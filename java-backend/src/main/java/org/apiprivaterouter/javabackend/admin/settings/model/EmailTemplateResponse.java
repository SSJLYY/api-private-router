package org.apiprivaterouter.javabackend.admin.settings.model;

public record EmailTemplateResponse(
        String event,
        String locale,
        String subject,
        String bodyHtml,
        String bodyText,
        boolean isOfficial,
        String updatedAt
) {
}
