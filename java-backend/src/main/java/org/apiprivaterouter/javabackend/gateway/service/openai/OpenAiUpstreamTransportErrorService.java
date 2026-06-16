package org.apiprivaterouter.javabackend.gateway.service.openai;

import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.UnknownHostException;
import java.util.Set;

@Service
public class OpenAiUpstreamTransportErrorService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiUpstreamTransportErrorService.class);
    private static final Set<String> PERSISTENT_MARKERS = Set.of(
            "authentication failed",
            "proxy authentication required",
            "connection refused",
            "no route to host",
            "network is unreachable",
            "no such host"
    );

    public boolean isPersistentTransportError(Exception ex) {
        if (ex instanceof ConnectException || ex instanceof NoRouteToHostException ||
                ex instanceof PortUnreachableException || ex instanceof UnknownHostException) {
            return true;
        }
        if (ex instanceof java.net.SocketTimeoutException) {
            return false;
        }
        if (ex instanceof IOException && ex.getCause() != null) {
            return isPersistentTransportError(ex.getCause() instanceof Exception e ? e : null);
        }
        String message = ex.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            for (String marker : PERSISTENT_MARKERS) {
                if (lowerMessage.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    public OpenAiUpstreamFailoverException handleTransportError(long accountId, Exception ex, String model) {
        log.warn("upstream transport error for account {} model {}: {}", accountId, model, ex.getMessage());

        if (ex instanceof java.net.SocketTimeoutException) {
            return new OpenAiUpstreamFailoverException(504, "upstream_timeout", "Upstream request timed out");
        }

        if (isPersistentTransportError(ex)) {
            return new OpenAiUpstreamFailoverException(502, "upstream_persistent_error",
                    "Persistent upstream transport error: " + ex.getMessage());
        }

        return new OpenAiUpstreamFailoverException(502, "upstream_transport_error",
                "Upstream transport error: " + ex.getMessage());
    }
}
