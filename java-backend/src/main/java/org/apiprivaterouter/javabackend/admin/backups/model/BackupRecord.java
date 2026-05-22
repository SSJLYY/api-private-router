package org.apiprivaterouter.javabackend.admin.backups.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackupRecord(
        String id,
        String status,
        String backup_type,
        String file_name,
        String s3_key,
        long size_bytes,
        String triggered_by,
        String error_message,
        String started_at,
        String finished_at,
        String expires_at,
        String progress,
        String restore_status,
        String restore_error,
        String restored_at
) {
}
