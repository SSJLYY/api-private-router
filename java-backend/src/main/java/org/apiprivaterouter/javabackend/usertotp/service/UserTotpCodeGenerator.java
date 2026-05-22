package org.apiprivaterouter.javabackend.usertotp.service;

import java.security.SecureRandom;

final class UserTotpCodeGenerator {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private UserTotpCodeGenerator() {
    }

    static String randomBase32Secret(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE32_ALPHABET.charAt(random.nextInt(BASE32_ALPHABET.length())));
        }
        return builder.toString();
    }
}
