package org.apiprivaterouter.javabackend.admin.backups.model;

public record BackupScheduleConfig(
        boolean enabled,
        String cron_expr,
        int retain_days,
        int retain_count
) {
}
