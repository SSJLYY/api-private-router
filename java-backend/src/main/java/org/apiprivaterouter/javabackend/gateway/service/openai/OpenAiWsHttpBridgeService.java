package org.apiprivaterouter.javabackend.gateway.service.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.*;

@Service
public class OpenAiWsHttpBridgeService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiWsHttpBridgeService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gateway.openai.ws.http-bridge-enabled:true}")
    private boolean httpBridgeEnabled;

    @Value("${gateway.openai.ws.http-bridge-threshold-bytes:15728640}")
    private long httpBridgeThresholdBytes;

    public boolean shouldBridge(byte[] payload, boolean hasPreviousResponseId) {
        return httpBridgeEnabled
                && payload.length > httpBridgeThresholdBytes
                && !hasPreviousResponseId;
    }

    public String prepareHttpBridgeBody(String wsPayload) {
        try {
            ObjectNode body = (ObjectNode) objectMapper.readTree(wsPayload);
            body.remove("type");
            body.remove("generate");
            body.remove("previous_response_id");
            body.put("stream", true);
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            log.error("failed to prepare WS HTTP bridge body: {}", ex.getMessage());
            return wsPayload;
        }
    }

    public String buildWsErrorEvent(String code, String message) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", "error");
            ObjectNode errorObj = event.putObject("error");
            errorObj.put("code", code);
            errorObj.put("message", message);
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            return "{\"type\":\"error\",\"error\":{\"code\":\"server_error\",\"message\":\"Internal error\"}}";
        }
    }

    public List<String> processSSEStream(String sseBody, String originalModel) {
        List<String> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(sseBody))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                        events.add("data: [DONE]\n\n");
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(data);
                        if (node.has("model") && node.get("model").asText().equals(originalModel) == false) {
                            ((ObjectNode) node).put("model", originalModel);
                        }
                        events.add("data: " + objectMapper.writeValueAsString(node) + "\n\n");
                    } catch (Exception ex) {
                        events.add(line + "\n\n");
                    }
                }
            }
        } catch (Exception ex) {
            log.error("failed to process SSE stream for WS bridge: {}", ex.getMessage());
        }
        return events;
    }
}
