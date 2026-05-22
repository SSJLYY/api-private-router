package org.apiprivaterouter.javabackend.common.security;

public record CurrentUser(long userId, String email, String role, long tokenVersion) {
}
