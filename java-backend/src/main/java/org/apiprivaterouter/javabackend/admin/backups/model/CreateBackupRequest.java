package org.apiprivaterouter.javabackend.admin.backups.model;

public record CreateBackupRequest(
        Integer expire_days
) {
}
