package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockResponseRelayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BedrockResponseRelay relay = new BedrockResponseRelay(objectMapper);

    @Test
    void mapsInvocationMetricsIntoSseUsage() throws Exception {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message_delta");
        ObjectNode delta = event.putObject("delta");
        delta.put("stop_reason", "end_turn");
        ObjectNode metrics = event.putObject("amazon-bedrock-invocationMetrics");
        metrics.put("inputTokenCount", 12);
        metrics.put("outputTokenCount", 7);

        BedrockEventStreamDecoder.BedrockEvent bedrockEvent = chunkEvent(event);

        List<String> sse = relay.toSseEvents(List.of(bedrockEvent));

        assertEquals(1, sse.size());
        assertTrue(sse.get(0).startsWith("event: message_delta\n"));
        assertTrue(sse.get(0).contains("\"input_tokens\":12"));
        assertTrue(sse.get(0).contains("\"output_tokens\":7"));
        assertTrue(sse.get(0).contains("\"cache_creation_input_tokens\":0"));
        assertTrue(sse.get(0).contains("\"cache_read_input_tokens\":0"));
    }

    @Test
    void aggregatesAnthropicStyleStreamIntoNormalizedJson() throws Exception {
        ObjectNode messageStart = objectMapper.createObjectNode();
        messageStart.put("type", "message_start");
        ObjectNode message = messageStart.putObject("message");
        message.put("id", "msg_1");
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", "claude-3");
        message.putObject("usage").put("input_tokens", 9);

        ObjectNode blockStart = objectMapper.createObjectNode();
        blockStart.put("type", "content_block_start");
        blockStart.put("index", 0);
        ObjectNode contentBlock = blockStart.putObject("content_block");
        contentBlock.put("type", "text");
        contentBlock.put("text", "");

        ObjectNode blockDelta = objectMapper.createObjectNode();
        blockDelta.put("type", "content_block_delta");
        blockDelta.put("index", 0);
        ObjectNode textDelta = blockDelta.putObject("delta");
        textDelta.put("type", "text_delta");
        textDelta.put("text", "Hello Bedrock");

        ObjectNode messageDelta = objectMapper.createObjectNode();
        messageDelta.put("type", "message_delta");
        ObjectNode delta = messageDelta.putObject("delta");
        delta.put("stop_reason", "end_turn");
        messageDelta.putObject("usage").put("output_tokens", 4);

        ObjectNode normalized = relay.toNormalizedJson(List.of(
                chunkEvent(messageStart),
                chunkEvent(blockStart),
                chunkEvent(blockDelta),
                chunkEvent(messageDelta)
        ));

        assertEquals("msg_1", normalized.path("id").asText());
        assertEquals("assistant", normalized.path("role").asText());
        assertEquals("claude-3", normalized.path("model").asText());
        assertEquals("Hello Bedrock", normalized.path("content").get(0).path("text").asText());
        assertEquals("end_turn", normalized.path("stop_reason").asText());
        assertEquals(9, normalized.path("usage").path("input_tokens").asInt());
        assertEquals(4, normalized.path("usage").path("output_tokens").asInt());
    }

    @Test
    void convertsExceptionEventIntoNormalizedErrorJson() throws Exception {
        ObjectNode exceptionPayload = objectMapper.createObjectNode();
        exceptionPayload.put("message", "validation failed");

        BedrockEventStreamDecoder.BedrockEvent event = new BedrockEventStreamDecoder.BedrockEvent(
                BedrockEventStreamDecoder.BedrockEventType.EXCEPTION,
                new BedrockEventStreamDecoder.EventStreamMessage(Map.of(), new byte[0], "exception", "", "application/json", "validationException"),
                exceptionPayload,
                new byte[0],
                null,
                "validationException",
                exceptionPayload
        );

        ObjectNode normalized = relay.toNormalizedJson(List.of(event));

        assertEquals("error", normalized.path("type").asText());
        assertEquals("validationException", normalized.path("error").path("type").asText());
        assertEquals("validation failed", normalized.path("error").path("message").asText());
    }

    private BedrockEventStreamDecoder.BedrockEvent chunkEvent(ObjectNode chunkJson) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(chunkJson);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("chunk").put("bytes", objectMapper.getFactory().createParser(bytes).readValueAsTree().toString());
        return new BedrockEventStreamDecoder.BedrockEvent(
                BedrockEventStreamDecoder.BedrockEventType.CHUNK,
                new BedrockEventStreamDecoder.EventStreamMessage(Map.of(), new byte[0], "event", "chunk", "application/json", ""),
                payload,
                bytes,
                chunkJson,
                "",
                null
        );
    }
}
