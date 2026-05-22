package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockEventStreamDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodesSplitChunkFramesAndChunkBytes() throws Exception {
        BedrockEventStreamDecoder decoder = new BedrockEventStreamDecoder(objectMapper);
        String anthropicEvent = """
                {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-3","usage":{"input_tokens":11}}}
                """;
        byte[] stream = encodeMessage(
                Map.of(
                        ":message-type", "event",
                        ":event-type", "chunk",
                        ":content-type", "application/json"
                ),
                """
                        {"chunk":{"bytes":"%s"}}
                        """.formatted(Base64.getEncoder().encodeToString(anthropicEvent.getBytes(StandardCharsets.UTF_8)))
                        .getBytes(StandardCharsets.UTF_8)
        );

        List<BedrockEventStreamDecoder.BedrockEvent> first = decoder.append(stream, 0, 9);
        List<BedrockEventStreamDecoder.BedrockEvent> second = decoder.append(stream, 9, stream.length - 9);
        decoder.finish();

        assertTrue(first.isEmpty());
        assertEquals(1, second.size());
        BedrockEventStreamDecoder.BedrockEvent event = second.get(0);
        assertTrue(event.isChunk());
        assertEquals("chunk", event.message().eventType());
        assertEquals("message_start", event.chunkJson().path("type").asText());
        assertEquals("msg_1", event.chunkJson().path("message").path("id").asText());
        assertArrayEquals(anthropicEvent.getBytes(StandardCharsets.UTF_8), event.chunkBytes());
    }

    @Test
    void rejectsCorruptedMessageCrc() throws Exception {
        BedrockEventStreamDecoder decoder = new BedrockEventStreamDecoder(objectMapper);
        byte[] frame = encodeMessage(
                Map.of(
                        ":message-type", "event",
                        ":event-type", "chunk",
                        ":content-type", "application/json"
                ),
                "{\"chunk\":{\"bytes\":\"e30=\"}}".getBytes(StandardCharsets.UTF_8)
        );
        frame[frame.length - 1] ^= 0x01;

        assertThrows(BedrockEventStreamDecoder.BedrockEventStreamException.class, () -> decoder.append(frame));
    }

    @Test
    void decodesExceptionFrames() throws Exception {
        BedrockEventStreamDecoder decoder = new BedrockEventStreamDecoder(objectMapper);
        byte[] frame = encodeMessage(
                Map.of(
                        ":message-type", "exception",
                        ":exception-type", "modelStreamErrorException",
                        ":content-type", "application/json"
                ),
                """
                        {"message":"stream failed","originalStatusCode":424,"originalMessage":"provider closed"}
                        """.getBytes(StandardCharsets.UTF_8)
        );

        List<BedrockEventStreamDecoder.BedrockEvent> events = decoder.append(frame);

        assertEquals(1, events.size());
        BedrockEventStreamDecoder.BedrockEvent event = events.get(0);
        assertTrue(event.isException());
        assertEquals("modelStreamErrorException", event.exceptionType());
        assertEquals("stream failed", event.exceptionPayload().path("message").asText());
        assertEquals(424, event.exceptionPayload().path("originalStatusCode").asInt());
    }

    private byte[] encodeMessage(Map<String, String> headers, byte[] payload) throws IOException {
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(headers).entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            headerOut.write(name.length);
            headerOut.write(name);
            headerOut.write(7);
            headerOut.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) value.length).array());
            headerOut.write(value);
        }
        byte[] headerBytes = headerOut.toByteArray();
        int totalLength = 16 + headerBytes.length + payload.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(intBytes(totalLength));
        out.write(intBytes(headerBytes.length));
        out.write(intBytes((int) crc32(out.toByteArray())));
        out.write(headerBytes);
        out.write(payload);
        out.write(intBytes((int) crc32(out.toByteArray())));
        return out.toByteArray();
    }

    private byte[] intBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private long crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }
}
