package org.apiprivaterouter.javabackend.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class TokenVersionResolver {

    private TokenVersionResolver() {
    }

    public static long resolve(String email, String passwordHash, long rawTokenVersion) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String normalizedPasswordHash = passwordHash == null ? "" : passwordHash;
        byte[] material = (normalizedEmail + "\n" + normalizedPasswordHash).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(material);
            long fingerprint = ByteBuffer.wrap(sum, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return rawTokenVersion ^ fingerprint;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to resolve user token version", ex);
        }
    }
}
