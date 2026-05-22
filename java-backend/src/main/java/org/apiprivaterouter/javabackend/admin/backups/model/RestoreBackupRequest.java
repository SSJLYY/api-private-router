package org.apiprivaterouter.javabackend.admin.backups.model;

import jakarta.validation.constraints.NotBlank;

public record RestoreBackupRequest(
        @NotBlank String password
) {
}
