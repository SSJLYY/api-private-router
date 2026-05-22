package org.apiprivaterouter.javabackend.admin.datamanagement.service;

import org.apiprivaterouter.javabackend.admin.datamanagement.model.DataManagementAgentHealthResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AdminDataManagementService {

    public static final String DEFAULT_SOCKET_PATH = "/tmp/api-private-router-datamanagement.sock";
    public static final String DEPRECATED_REASON = "DATA_MANAGEMENT_DEPRECATED";

    public DataManagementAgentHealthResponse getAgentHealth() {
        return new DataManagementAgentHealthResponse(
                false,
                DEPRECATED_REASON,
                DEFAULT_SOCKET_PATH,
                null
        );
    }

    public StructuredApiErrorException deprecatedError() {
        return new StructuredApiErrorException(
                503,
                DEPRECATED_REASON,
                "data management feature is deprecated",
                Map.of("socket_path", DEFAULT_SOCKET_PATH)
        );
    }
}
