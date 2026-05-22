package org.apiprivaterouter.javabackend.admin.backups.model;

public record BackupConnectionTestResponse(
        boolean ok,
        String message
) {
}
