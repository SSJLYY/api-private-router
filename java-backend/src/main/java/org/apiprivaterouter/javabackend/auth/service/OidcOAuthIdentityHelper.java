package org.apiprivaterouter.javabackend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class OidcOAuthIdentityHelper {

    public static final String SYNTHETIC_EMAIL_DOMAIN = "@oidc-connect.invalid";

    private OidcOAuthIdentityHelper() {
    }

    public static String identityKey(String issuer, String subject) {
        return normalizeIssuer(issuer) + "\u001f" + trimToEmpty(subject);
    }

    public static String syntheticEmailFromIdentityKey(String identityKey) {
        String normalized = trimToEmpty(identityKey);
        if (normalized.isEmpty()) {
            return "";
        }
        byte[] digest = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        byte[] prefix = new byte[16];
        System.arraycopy(digest, 0, prefix, 0, prefix.length);
        return "oidc-" + HexFormat.of().formatHex(prefix) + SYNTHETIC_EMAIL_DOMAIN;
    }

    public static String fallbackUsername(String subject) {
        String normalized = trimToEmpty(subject);
        if (normalized.isEmpty()) {
            return "oidc_user";
        }
        return "oidc_" + HexFormat.of().formatHex(sha256(normalized.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    public static boolean isReservedSyntheticEmail(String email) {
        return trimToEmpty(email).toLowerCase(Locale.ROOT).endsWith(SYNTHETIC_EMAIL_DOMAIN);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to hash oidc identity", ex);
        }
    }

    private static String normalizeIssuer(String issuer) {
        return trimToEmpty(issuer).toLowerCase(Locale.ROOT);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
