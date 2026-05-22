package org.apiprivaterouter.javabackend.admin.backups.service;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupConnectionTestResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupDownloadUrlResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupListResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupRecord;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupS3Config;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupScheduleConfig;
import org.apiprivaterouter.javabackend.admin.backups.repository.AdminBackupsRepository;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

@Service
public class AdminBackupsService {

    private static final String KEY_S3_CONFIG = "backup_s3_config";
    private static final String KEY_SCHEDULE = "backup_schedule";
    private static final String KEY_RECORDS = "backup_records";
    private static final int MAX_RECORDS = 100;
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AdminBackupsRepository repository;
    private final JsonHelper jsonHelper;
    private final BackupCrypto backupCrypto;
    private final BackupCronSupport cronSupport;
    private final S3CompatibleBackupStoreFactory storeFactory;
    private final PostgresCommandSupport postgresCommandSupport;

    private final Object operationLock = new Object();
    private final Object recordsLock = new Object();
    private final AtomicBoolean backingUp = new AtomicBoolean(false);
    private final AtomicBoolean restoring = new AtomicBoolean(false);

    public AdminBackupsService(
            AdminBackupsRepository repository,
            JsonHelper jsonHelper,
            BackupCrypto backupCrypto,
            BackupCronSupport cronSupport,
            S3CompatibleBackupStoreFactory storeFactory,
            PostgresCommandSupport postgresCommandSupport
    ) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.backupCrypto = backupCrypto;
        this.cronSupport = cronSupport;
        this.storeFactory = storeFactory;
        this.postgresCommandSupport = postgresCommandSupport;
    }

    public BackupS3Config getS3Config() {
        BackupS3Config config = loadS3Config();
        return config == null ? new BackupS3Config("", "", "", "", "", "", false) : config.withoutSecret();
    }

    public BackupS3Config updateS3Config(BackupS3Config request) {
        BackupS3Config existing = loadS3Config();
        String secret = trimToNull(request.secret_access_key());
        if (secret == null && existing != null) {
            secret = existing.secret_access_key();
        }
        BackupS3Config normalized = new BackupS3Config(
                defaultString(request.endpoint()),
                defaultRegion(request.region()),
                defaultString(request.bucket()),
                defaultString(request.access_key_id()),
                secret == null ? "" : backupCrypto.encrypt(secret),
                defaultPrefix(request.prefix()),
                Boolean.TRUE.equals(request.force_path_style())
        );
        repository.upsertSetting(KEY_S3_CONFIG, jsonHelper.writeJson(normalized));
        return new BackupS3Config(
                normalized.endpoint(),
                normalized.region(),
                normalized.bucket(),
                normalized.access_key_id(),
                "",
                normalized.prefix(),
                normalized.force_path_style()
        );
    }

    public BackupConnectionTestResponse testS3Connection(BackupS3Config request) {
        try {
            BackupS3Config config = resolveTestingConfig(request);
            storeFactory.create(config).headBucket();
            return new BackupConnectionTestResponse(true, "connection successful");
        } catch (Exception ex) {
            return new BackupConnectionTestResponse(false, ex.getMessage());
        }
    }

    public BackupScheduleConfig getSchedule() {
        String raw = repository.getSetting(KEY_SCHEDULE);
        BackupScheduleConfig config = jsonHelper.readObject(raw, BackupScheduleConfig.class);
        return config == null ? new BackupScheduleConfig(false, "", 0, 0) : config;
    }

    public BackupScheduleConfig updateSchedule(BackupScheduleConfig config) {
        String cron = trimToNull(config.cron_expr());
        if (config.enabled() && cron == null) {
            throw new StructuredApiErrorException(400, "INVALID_CRON", "cron expression is required when schedule is enabled");
        }
        if (cron != null) {
            cronSupport.validate(cron);
        }
        BackupScheduleConfig normalized = new BackupScheduleConfig(config.enabled(), defaultString(cron), Math.max(config.retain_days(), 0), Math.max(config.retain_count(), 0));
        repository.upsertSetting(KEY_SCHEDULE, jsonHelper.writeJson(normalized));
        return normalized;
    }

    public BackupRecord createBackupAsync(int expireDays) {
        synchronized (operationLock) {
            if (backingUp.get()) {
                throw new StructuredApiErrorException(409, "BACKUP_IN_PROGRESS", "a backup is already in progress");
            }
            backingUp.set(true);
        }
        BackupS3Config config = requireS3Configured();
        String fileName = buildFileName(postgresCommandSupport.connectionInfo().database());
        BackupRecord initial = new BackupRecord(
                shortId(),
                "running",
                "postgres",
                fileName,
                buildS3Key(config, fileName),
                0L,
                "manual",
                null,
                now(),
                null,
                expireDays > 0 ? OffsetDateTime.now(ZoneOffset.UTC).plusDays(expireDays).format(RFC3339) : null,
                "pending",
                null,
                null,
                null
        );
        saveRecord(initial);
        new Thread(() -> {
            try {
                runBackup(initial, config);
            } finally {
                backingUp.set(false);
            }
        }, "admin-backup-create").start();
        return initial;
    }

    public BackupListResponse listBackups() {
        List<BackupRecord> records = new ArrayList<>(loadRecords());
        records.sort(Comparator.comparing(BackupRecord::started_at, Comparator.nullsLast(String::compareTo)).reversed());
        return new BackupListResponse(records);
    }

    public BackupRecord getBackup(String id) {
        return loadRecords().stream()
                .filter(item -> id.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new StructuredApiErrorException(404, "BACKUP_NOT_FOUND", "backup record not found"));
    }

    public void deleteBackup(String id) {
        synchronized (recordsLock) {
            List<BackupRecord> records = new ArrayList<>(loadRecords());
            BackupRecord found = null;
            List<BackupRecord> remaining = new ArrayList<>();
            for (BackupRecord record : records) {
                if (id.equals(record.id())) {
                    found = record;
                } else {
                    remaining.add(record);
                }
            }
            if (found == null) {
                throw new StructuredApiErrorException(404, "BACKUP_NOT_FOUND", "backup record not found");
            }
            if ("completed".equals(found.status()) && trimToNull(found.s3_key()) != null) {
                try {
                    storeFactory.create(requireS3Configured()).delete(found.s3_key());
                } catch (Exception ignored) {
                }
            }
            saveRecordsLocked(remaining);
        }
    }

    public BackupDownloadUrlResponse getDownloadUrl(String id) {
        BackupRecord record = getBackup(id);
        if (!"completed".equals(record.status())) {
            throw new StructuredApiErrorException(400, "BACKUP_NOT_COMPLETED", "backup is not completed");
        }
        String url = storeFactory.create(requireS3Configured()).presignUrl(record.s3_key(), Duration.ofHours(1));
        return new BackupDownloadUrlResponse(url);
    }

    public BackupRecord restoreBackupAsync(String id, String password, CurrentUser admin) {
        verifyAdminPassword(admin.userId(), password);
        synchronized (operationLock) {
            if (restoring.get()) {
                throw new StructuredApiErrorException(409, "RESTORE_IN_PROGRESS", "a restore is already in progress");
            }
            restoring.set(true);
        }
        BackupRecord record = getBackup(id);
        if (!"completed".equals(record.status())) {
            restoring.set(false);
            throw new StructuredApiErrorException(400, "BACKUP_NOT_COMPLETED", "can only restore from a completed backup");
        }
        BackupRecord restoringRecord = copy(record, overrides(
                "restore_status", "running",
                "restore_error", null,
                "restored_at", null
        ));
        saveRecord(restoringRecord);
        BackupS3Config config = requireS3Configured();
        new Thread(() -> {
            try {
                runRestore(restoringRecord, config);
            } finally {
                restoring.set(false);
            }
        }, "admin-backup-restore").start();
        return restoringRecord;
    }

    @Scheduled(cron = "0 * * * * *")
    public void tickSchedule() {
        BackupScheduleConfig schedule = getSchedule();
        if (!schedule.enabled() || trimToNull(schedule.cron_expr()) == null || backingUp.get()) {
            return;
        }
        Instant now = Instant.now();
        Instant previousMinute = now.minusSeconds(60);
        Instant next = cronSupport.computeNextRun(schedule.cron_expr(), previousMinute.minusSeconds(60));
        if (next.isAfter(previousMinute) && !next.isAfter(now.plusSeconds(1))) {
            try {
                BackupS3Config config = requireS3Configured();
                synchronized (operationLock) {
                    if (backingUp.get()) {
                        return;
                    }
                    backingUp.set(true);
                }
                String fileName = buildFileName(postgresCommandSupport.connectionInfo().database());
                BackupRecord initial = new BackupRecord(
                        shortId(),
                        "running",
                        "postgres",
                        fileName,
                        buildS3Key(config, fileName),
                        0L,
                        "scheduled",
                        null,
                        now(),
                        null,
                        schedule.retain_days() > 0 ? OffsetDateTime.now(ZoneOffset.UTC).plusDays(schedule.retain_days()).format(RFC3339) : null,
                        "pending",
                        null,
                        null,
                        null
                );
                saveRecord(initial);
                new Thread(() -> {
                    try {
                        runBackup(initial, config);
                        cleanupExpiredBackups(schedule);
                    } finally {
                        backingUp.set(false);
                    }
                }, "admin-backup-scheduled").start();
            } catch (Exception ex) {
                backingUp.set(false);
            }
        }
    }

    private void cleanupExpiredBackups(BackupScheduleConfig schedule) {
        synchronized (recordsLock) {
            List<BackupRecord> records = new ArrayList<>(loadRecords());
            records.sort(Comparator.comparing(BackupRecord::started_at, Comparator.nullsLast(String::compareTo)).reversed());
            List<BackupRecord> keep = new ArrayList<>();
            List<BackupRecord> delete = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                BackupRecord record = records.get(i);
                boolean shouldDelete = false;
                if (schedule.retain_count() > 0 && i >= schedule.retain_count()) {
                    shouldDelete = true;
                }
                if (schedule.retain_days() > 0 && trimToNull(record.started_at()) != null) {
                    OffsetDateTime startedAt = OffsetDateTime.parse(record.started_at());
                    if (startedAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(schedule.retain_days()))) {
                        shouldDelete = true;
                    }
                }
                if (shouldDelete && "completed".equals(record.status())) {
                    delete.add(record);
                } else {
                    keep.add(record);
                }
            }
            BackupS3Config config = loadS3Config();
            if (config != null && config.isConfigured()) {
                BackupObjectStore store = storeFactory.create(config);
                for (BackupRecord record : delete) {
                    try {
                        store.delete(record.s3_key());
                    } catch (Exception ignored) {
                    }
                }
            }
            saveRecordsLocked(keep);
        }
    }

    private void runBackup(BackupRecord record, BackupS3Config config) {
        saveRecord(copy(record, overrides("progress", "dumping")));
        PostgresCommandSupport.DbConnectionInfo db = postgresCommandSupport.connectionInfo();
        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-h", db.host(),
                "-p", String.valueOf(db.port()),
                "-U", db.username(),
                "-d", db.database(),
                "--no-owner",
                "--no-privileges"
        );
        pb.environment().put("PGPASSWORD", defaultString(db.password()));
        try {
            Process process = pb.start();
            saveRecord(copy(record, overrides("progress", "uploading")));
            Path tempFile = Files.createTempFile("api-private-router-backup-", ".sql.gz");
            try (InputStream dump = process.getInputStream();
                 GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(tempFile))) {
                dump.transferTo(gzip);
            }
            int exit = process.waitFor();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (exit != 0) {
                Files.deleteIfExists(tempFile);
                throw new IOException("pg_dump failed: " + error);
            }
            long size;
            try (InputStream gzipped = Files.newInputStream(tempFile)) {
                size = storeFactory.create(config).upload(record.s3_key(), gzipped, "application/gzip");
            } finally {
                Files.deleteIfExists(tempFile);
            }
            saveRecord(copy(record, overrides(
                    "status", "completed",
                    "size_bytes", size,
                    "progress", "",
                    "finished_at", now(),
                    "error_message", null
            )));
        } catch (Exception ex) {
            saveRecord(copy(record, overrides(
                    "status", "failed",
                    "progress", "",
                    "finished_at", now(),
                    "error_message", ex.getMessage()
            )));
        }
    }

    private void runRestore(BackupRecord record, BackupS3Config config) {
        PostgresCommandSupport.DbConnectionInfo db = postgresCommandSupport.connectionInfo();
        ProcessBuilder pb = new ProcessBuilder(
                "psql",
                "-h", db.host(),
                "-p", String.valueOf(db.port()),
                "-U", db.username(),
                "-d", db.database()
        );
        pb.environment().put("PGPASSWORD", defaultString(db.password()));
        try (InputStream remote = storeFactory.create(config).download(record.s3_key());
             InputStream gzip = new java.util.zip.GZIPInputStream(remote)) {
            Process process = pb.start();
            try (OutputStream stdin = process.getOutputStream()) {
                gzip.transferTo(stdin);
            }
            int exit = process.waitFor();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (exit != 0) {
                throw new IOException("psql restore failed: " + error);
            }
            saveRecord(copy(record, overrides(
                    "restore_status", "completed",
                    "restore_error", null,
                    "restored_at", now()
            )));
        } catch (Exception ex) {
            saveRecord(copy(record, overrides(
                    "restore_status", "failed",
                    "restore_error", ex.getMessage()
            )));
        }
    }

    private BackupS3Config resolveTestingConfig(BackupS3Config request) {
        BackupS3Config existing = loadS3Config();
        String secret = trimToNull(request.secret_access_key());
        if (secret == null && existing != null) {
            secret = existing.secret_access_key();
        }
        BackupS3Config config = new BackupS3Config(
                firstNonBlank(request.endpoint(), existing == null ? null : existing.endpoint()),
                defaultRegion(firstNonBlank(request.region(), existing == null ? null : existing.region())),
                firstNonBlank(request.bucket(), existing == null ? null : existing.bucket()),
                firstNonBlank(request.access_key_id(), existing == null ? null : existing.access_key_id()),
                secret,
                defaultPrefix(firstNonBlank(request.prefix(), existing == null ? null : existing.prefix())),
                request.force_path_style() != null ? request.force_path_style() : existing != null && Boolean.TRUE.equals(existing.force_path_style())
        );
        if (!config.isConfigured()) {
            throw new StructuredApiErrorException(400, "BACKUP_S3_NOT_CONFIGURED", "incomplete S3 config: bucket, access_key_id, secret_access_key are required");
        }
        return config;
    }

    private BackupS3Config requireS3Configured() {
        BackupS3Config config = loadS3Config();
        if (config == null || !config.isConfigured()) {
            throw new StructuredApiErrorException(400, "BACKUP_S3_NOT_CONFIGURED", "backup S3 storage is not configured");
        }
        return config;
    }

    private BackupS3Config loadS3Config() {
        BackupS3Config config = jsonHelper.readObject(repository.getSetting(KEY_S3_CONFIG), BackupS3Config.class);
        if (config == null) {
            return null;
        }
        return new BackupS3Config(
                defaultString(config.endpoint()),
                defaultRegion(config.region()),
                defaultString(config.bucket()),
                defaultString(config.access_key_id()),
                backupCrypto.decrypt(config.secret_access_key()),
                defaultPrefix(config.prefix()),
                Boolean.TRUE.equals(config.force_path_style())
        );
    }

    private List<BackupRecord> loadRecords() {
        synchronized (recordsLock) {
            return loadRecordsLocked();
        }
    }

    private void saveRecord(BackupRecord record) {
        synchronized (recordsLock) {
            List<BackupRecord> records = new ArrayList<>(loadRecordsLocked());
            boolean updated = false;
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).id().equals(record.id())) {
                    records.set(i, record);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                records.add(record);
            }
            if (records.size() > MAX_RECORDS) {
                records = new ArrayList<>(records.subList(records.size() - MAX_RECORDS, records.size()));
            }
            saveRecordsLocked(records);
        }
    }

    private void saveRecordsLocked(List<BackupRecord> records) {
        repository.upsertSetting(KEY_RECORDS, jsonHelper.writeJson(records));
    }

    private List<BackupRecord> loadRecordsLocked() {
        List<BackupRecord> records = jsonHelper.readList(repository.getSetting(KEY_RECORDS), BackupRecord.class);
        return records == null ? List.of() : records;
    }

    private void verifyAdminPassword(long userId, String password) {
        AdminBackupsRepository.AdminPasswordRow row = repository.findAdminPasswordByUserId(userId);
        if (row == null || !"admin".equalsIgnoreCase(defaultString(row.role()))) {
            throw new StructuredApiErrorException(401, "UNAUTHORIZED", "admin authentication required");
        }
        if (trimToNull(password) == null || trimToNull(row.password_hash()) == null || !BCrypt.checkpw(password, row.password_hash())) {
            throw new StructuredApiErrorException(400, "INVALID_ADMIN_PASSWORD", "incorrect admin password");
        }
    }

    private BackupRecord copy(BackupRecord base, Map<String, Object> overrides) {
        return new BackupRecord(
                string(overrides.getOrDefault("id", base.id())),
                string(overrides.getOrDefault("status", base.status())),
                string(overrides.getOrDefault("backup_type", base.backup_type())),
                string(overrides.getOrDefault("file_name", base.file_name())),
                string(overrides.getOrDefault("s3_key", base.s3_key())),
                longValue(overrides.getOrDefault("size_bytes", base.size_bytes())),
                string(overrides.getOrDefault("triggered_by", base.triggered_by())),
                string(overrides.getOrDefault("error_message", base.error_message())),
                string(overrides.getOrDefault("started_at", base.started_at())),
                string(overrides.getOrDefault("finished_at", base.finished_at())),
                string(overrides.getOrDefault("expires_at", base.expires_at())),
                string(overrides.getOrDefault("progress", base.progress())),
                string(overrides.getOrDefault("restore_status", base.restore_status())),
                string(overrides.getOrDefault("restore_error", base.restore_error())),
                string(overrides.getOrDefault("restored_at", base.restored_at()))
        );
    }

    private static String buildS3Key(BackupS3Config config, String fileName) {
        String prefix = trimToNull(config.prefix());
        if (prefix == null) {
            prefix = "backups";
        }
        String datePath = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return prefix.replaceAll("/+$", "") + "/" + datePath + "/" + fileName;
    }

    private static String buildFileName(String database) {
        String safeDb = (database == null || database.isBlank() ? "api-private-router" : database).replaceAll("[^a-zA-Z0-9_-]", "_");
        return safeDb + "_" + OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".sql.gz";
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(RFC3339);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String firstNonBlank(String first, String second) {
        String left = trimToNull(first);
        return left != null ? left : defaultString(second);
    }

    private static String defaultPrefix(String prefix) {
        String normalized = trimToNull(prefix);
        return normalized == null ? "backups" : normalized;
    }

    private static String defaultRegion(String region) {
        String normalized = trimToNull(region);
        return normalized == null ? "auto" : normalized;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Map<String, Object> overrides(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
