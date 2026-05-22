package org.apiprivaterouter.javabackend.admin.backups.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupConnectionTestResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupDeleteResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupDownloadUrlResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupListResponse;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupRecord;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupS3Config;
import org.apiprivaterouter.javabackend.admin.backups.model.BackupScheduleConfig;
import org.apiprivaterouter.javabackend.admin.backups.model.CreateBackupRequest;
import org.apiprivaterouter.javabackend.admin.backups.model.RestoreBackupRequest;
import org.apiprivaterouter.javabackend.admin.backups.service.AdminBackupsService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/backups")
public class AdminBackupsController {

    private final AdminBackupsService service;
    private final CurrentUserContext currentUserContext;

    public AdminBackupsController(AdminBackupsService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/s3-config")
    public ApiResponse<BackupS3Config> getS3Config() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getS3Config());
    }

    @PutMapping("/s3-config")
    public ApiResponse<BackupS3Config> updateS3Config(@RequestBody BackupS3Config request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateS3Config(request));
    }

    @PostMapping("/s3-config/test")
    public ApiResponse<BackupConnectionTestResponse> testS3Connection(@RequestBody BackupS3Config request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.testS3Connection(request));
    }

    @GetMapping("/schedule")
    public ApiResponse<BackupScheduleConfig> getSchedule() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getSchedule());
    }

    @PutMapping("/schedule")
    public ApiResponse<BackupScheduleConfig> updateSchedule(@RequestBody BackupScheduleConfig request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateSchedule(request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BackupRecord>> createBackup(@RequestBody(required = false) CreateBackupRequest request) {
        currentUserContext.requireAdmin();
        int expireDays = request == null || request.expire_days() == null ? 14 : request.expire_days();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.createBackupAsync(expireDays)));
    }

    @GetMapping
    public ApiResponse<BackupListResponse> listBackups() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listBackups());
    }

    @GetMapping("/{id}")
    public ApiResponse<BackupRecord> getBackup(@PathVariable String id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getBackup(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<BackupDeleteResponse> deleteBackup(@PathVariable String id) {
        currentUserContext.requireAdmin();
        service.deleteBackup(id);
        return ApiResponse.success(new BackupDeleteResponse(true));
    }

    @GetMapping("/{id}/download-url")
    public ApiResponse<BackupDownloadUrlResponse> getDownloadUrl(@PathVariable String id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getDownloadUrl(id));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<BackupRecord>> restore(@PathVariable String id, @Valid @RequestBody RestoreBackupRequest request) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.restoreBackupAsync(id, request.password(), admin)));
    }
}
