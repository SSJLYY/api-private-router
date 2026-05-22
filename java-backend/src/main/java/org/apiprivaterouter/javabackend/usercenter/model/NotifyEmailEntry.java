package org.apiprivaterouter.javabackend.usercenter.model;

public record NotifyEmailEntry(
        String email,
        boolean disabled,
        boolean verified
) {
    public NotifyEmailEntry(String email, boolean disabled) {
        this(email, disabled, false);
    }
}
