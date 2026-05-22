package org.apiprivaterouter.javabackend.common.api;

public class AnthropicApiErrorException extends RuntimeException {

    private final int status;
    private final String errorType;

    public AnthropicApiErrorException(int status, String errorType, String message) {
        super(message);
        this.status = status;
        this.errorType = errorType;
    }

    public int getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }
}
