package org.apiprivaterouter.javabackend.admin.settings.model;

public record TestSmtpRequest(
        String smtp_host,
        Integer smtp_port,
        String smtp_username,
        String smtp_password,
        boolean smtp_use_tls
) {
}
