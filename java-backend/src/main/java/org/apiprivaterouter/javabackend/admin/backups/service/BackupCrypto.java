package org.apiprivaterouter.javabackend.admin.backups.service;

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

    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String PREFIX = "enc:v1:";

    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    public BackupCrypto(@Value("${api-private-router.jwt.secret:}") String secret) {
        String material = secret == null || secret.isBlank() ? "api-private-router-java-backup-default-key" : secret;
        this.key = sha256(material);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
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
}
