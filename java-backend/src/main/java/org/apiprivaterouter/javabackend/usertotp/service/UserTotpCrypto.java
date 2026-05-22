package org.apiprivaterouter.javabackend.usertotp.service;

import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class UserTotpCrypto {

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    public String encrypt(String plaintext) {
        SecretKeySpec keySpec = requireKeySpec();
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encrypt totp secret", ex);
        }
    }

    public String decrypt(String ciphertext) {
        SecretKeySpec keySpec = requireKeySpec();
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < NONCE_LENGTH_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            byte[] encrypted = new byte[combined.length - NONCE_LENGTH_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH_BYTES);
            System.arraycopy(combined, NONCE_LENGTH_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decrypt totp secret", ex);
        }
    }

    private SecretKeySpec requireKeySpec() {
        String raw = System.getenv("TOTP_ENCRYPTION_KEY");
        if (raw == null || raw.isBlank()) {
            throw new HttpStatusException(503, "totp encryption is not configured");
        }
        byte[] keyBytes = decodeHex(raw.trim());
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new HttpStatusException(503, "totp encryption key is invalid");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] decodeHex(String raw) {
        if ((raw.length() & 1) == 1) {
            throw new HttpStatusException(503, "totp encryption key is invalid");
        }
        byte[] out = new byte[raw.length() / 2];
        for (int i = 0; i < raw.length(); i += 2) {
            int value;
            try {
                value = Integer.parseInt(raw.substring(i, i + 2), 16);
            } catch (NumberFormatException ex) {
                throw new HttpStatusException(503, "totp encryption key is invalid");
            }
            out[i / 2] = (byte) value;
        }
        return out;
    }
}
