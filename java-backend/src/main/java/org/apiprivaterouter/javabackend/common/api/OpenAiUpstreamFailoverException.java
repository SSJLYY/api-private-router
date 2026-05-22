package org.apiprivaterouter.javabackend.common.api;

public class OpenAiUpstreamFailoverException extends RuntimeException {

    private final int upstreamStatus;
    private final String errorType;

    public OpenAiUpstreamFailoverException(int upstreamStatus, String errorType, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
        this.errorType = errorType == null || errorType.isBlank() ? "rate_limit_error" : errorType;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getErrorType() {
        return errorType;
    }

    public OpenAiApiErrorException toOpenAiApiErrorException() {
        return new OpenAiApiErrorException(upstreamStatus, errorType, getMessage());
    }

    public AnthropicApiErrorException toAnthropicApiErrorException() {
        return new AnthropicApiErrorException(upstreamStatus, "api_error", getMessage());
    }
}
