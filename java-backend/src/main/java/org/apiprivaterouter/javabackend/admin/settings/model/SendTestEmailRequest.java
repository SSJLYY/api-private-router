package org.apiprivaterouter.javabackend.admin.settings.model;

public record SendTestEmailRequest(
        String email,
        String smtp_host,
        Integer smtp_port,
        String smtp_username,
        String smtp_password,
        String smtp_from_email,
        String smtp_from_name,
        boolean smtp_use_tls
) {
}
