package org.apiprivaterouter.javabackend.admin.backups.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class BackupCrypto {

    private static final Logger log = LoggerFactory.getLogger(BackupCrypto.class);
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String PREFIX = "enc:v1:";

    private final byte[] key;
    private final boolean usingDefaultKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public BackupCrypto(@Value("${api-private-router.backup.encryption-key:${API_PRIVATE_ROUTER_BACKUP_ENCRYPTION_KEY:}}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            // Fail-safe instead of failing startup: derive a per-process random key. Backups are
            // optional and not used until explicitly configured in the admin UI, so blocking the
            // entire application boot is disproportionate. A warning is logged so operators know
            // they must set a fixed key before relying on backups.
            this.key = randomKey();
            this.usingDefaultKey = true;
            log.warn("Backup encryption key is not configured (API_PRIVATE_ROUTER_BACKUP_ENCRYPTION_KEY). "
                    + "Using an ephemeral random key: previously encrypted backup secrets cannot be decrypted "
                    + "after a restart. Set a fixed key before using the backup feature.");
        } else {
            this.key = sha256(encryptionKey);
            this.usingDefaultKey = false;
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }
        if (usingDefaultKey) {
            throw new IllegalStateException(
                    "Backup encryption key is not configured. Set API_PRIVATE_ROUTER_BACKUP_ENCRYPTION_KEY "
                            + "before encrypting backup secrets.");
        }
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to encrypt backup secret", ex);
        }
    }

    public String decrypt(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return rawValue;
        }
        if (!rawValue.startsWith(PREFIX)) {
            return rawValue;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(rawValue.substring(PREFIX.length()));
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            byte[] encrypted = new byte[payload.length - GCM_NONCE_LENGTH];
            System.arraycopy(payload, 0, nonce, 0, nonce.length);
            System.arraycopy(payload, nonce.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return rawValue;
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to initialize backup crypto", ex);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
