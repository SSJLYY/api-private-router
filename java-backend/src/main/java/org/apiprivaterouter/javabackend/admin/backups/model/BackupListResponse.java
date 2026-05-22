package org.apiprivaterouter.javabackend.admin.backups.model;

import java.util.List;

public record BackupListResponse(
        List<BackupRecord> items
) {
}
