package org.apiprivaterouter.javabackend.admin.backups.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackupS3Config(
        String endpoint,
        String region,
        String bucket,
        String access_key_id,
        String secret_access_key,
        String prefix,
        Boolean force_path_style
) {
    public boolean isConfigured() {
        return notBlank(bucket) && notBlank(access_key_id) && notBlank(secret_access_key);
    }

    public BackupS3Config withoutSecret() {
        return new BackupS3Config(endpoint, region, bucket, access_key_id, "", prefix, force_path_style);
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
