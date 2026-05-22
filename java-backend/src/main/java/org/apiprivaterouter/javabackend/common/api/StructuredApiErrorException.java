package org.apiprivaterouter.javabackend.common.api;

import java.util.Map;

public class StructuredApiErrorException extends RuntimeException {

    private final int status;
    private final String reason;
    private final Map<String, Object> metadata;

    public StructuredApiErrorException(int status, String reason, String message) {
        this(status, reason, message, Map.of());
    }

    public StructuredApiErrorException(int status, String reason, String message, Map<String, Object> metadata) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public int getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
