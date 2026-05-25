package org.apiprivaterouter.javabackend.channelmonitor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ChannelMonitorCrypto {

    private static final Logger log = LoggerFactory.getLogger(ChannelMonitorCrypto.class);
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final byte[] keyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    public ChannelMonitorCrypto() {
        String raw = System.getenv("TOTP_ENCRYPTION_KEY");
        byte[] tmp = null;
        if (raw != null && !raw.isBlank()) {
            tmp = decodeHex(raw.trim());
            if (tmp.length != KEY_LENGTH_BYTES) {
                log.warn("TOTP_ENCRYPTION_KEY must be 64 hex chars, encryption disabled");
                tmp = null;
            }
        } else {
            log.warn("TOTP_ENCRYPTION_KEY not set, channel monitor encryption disabled");
        }
        this.keyBytes = tmp;
    }

    public String encrypt(String plaintext) {
        if (keyBytes == null) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY not configured, channel monitor encryption unavailable");
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to encrypt channel monitor api key", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (keyBytes == null) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY not configured, channel monitor encryption unavailable");
        }
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
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to decrypt channel monitor api key", ex);
        }
    }

    private byte[] decodeHex(String raw) {
        if ((raw.length() & 1) == 1) {
            throw new IllegalStateException("hex string length must be even");
        }
        byte[] out = new byte[raw.length() / 2];
        for (int i = 0; i < raw.length(); i += 2) {
            int value = Integer.parseInt(raw.substring(i, i + 2), 16);
            out[i / 2] = (byte) value;
        }
        return out;
    }
}
