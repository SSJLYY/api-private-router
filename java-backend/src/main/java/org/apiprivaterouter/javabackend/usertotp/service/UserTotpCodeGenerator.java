package org.apiprivaterouter.javabackend.usertotp.service;

import java.security.SecureRandom;

final class UserTotpCodeGenerator {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private UserTotpCodeGenerator() {
    }

    static String randomBase32Secret(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE32_ALPHABET.charAt(RANDOM.nextInt(BASE32_ALPHABET.length())));
        }
        return builder.toString();
    }
}
