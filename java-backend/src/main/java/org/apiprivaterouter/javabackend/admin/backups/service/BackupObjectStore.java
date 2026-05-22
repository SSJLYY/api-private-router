package org.apiprivaterouter.javabackend.admin.backups.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

public interface BackupObjectStore {

    long upload(String key, InputStream body, String contentType) throws IOException, InterruptedException;

    InputStream download(String key) throws IOException, InterruptedException;

    void delete(String key) throws IOException, InterruptedException;

    String presignUrl(String key, Duration expiry);

    void headBucket() throws IOException, InterruptedException;
}
