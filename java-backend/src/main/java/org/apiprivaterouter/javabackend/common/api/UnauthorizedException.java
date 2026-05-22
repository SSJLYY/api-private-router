package org.apiprivaterouter.javabackend.common.api;

public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
