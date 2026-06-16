package org.apiprivaterouter.javabackend.gateway.service.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

@Service
public class OpenAiStreamErrorEventService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiStreamErrorEventService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void writeResponsesFailedSSE(HttpServletResponse response, String requestId,
                                          String model, String errorCode, String errorMessage) throws IOException {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "response.failed");

        ObjectNode responseObj = event.putObject("response");
        responseObj.put("id", requestId != null ? requestId : "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        responseObj.put("object", "response");
        responseObj.put("model", model != null ? model : "");
        responseObj.put("status", "failed");
        responseObj.putArray("output");

        ObjectNode errorObj = responseObj.putObject("error");
        errorObj.put("code", errorCode != null ? errorCode : "server_error");
        errorObj.put("message", errorMessage != null ? errorMessage : "An internal error occurred");

        response.getWriter().write("event: response.failed\n");
        response.getWriter().write("data: " + objectMapper.writeValueAsString(event) + "\n\n");
        response.getWriter().flush();
    }

    public String mapResponsesErrorCode(int httpStatus, String errorType) {
        if (errorType != null) {
            return switch (errorType) {
                case "rate_limit_error" -> "rate_limit_exceeded";
                case "invalid_request_error" -> "invalid_request";
                case "authentication_error" -> "authentication_error";
                case "insufficient_quota" -> "insufficient_quota";
                default -> "server_error";
            };
        }
        return switch (httpStatus) {
            case 400 -> "invalid_request";
            case 401 -> "authentication_error";
            case 403 -> "insufficient_quota";
            case 429 -> "rate_limit_exceeded";
            default -> "server_error";
        };
    }

    public String synthesizeResponseId(String requestId) {
        if (requestId != null && requestId.startsWith("resp_")) {
            return requestId;
        }
        return "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
