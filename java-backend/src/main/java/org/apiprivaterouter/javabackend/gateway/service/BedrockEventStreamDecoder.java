package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

public final class BedrockEventStreamDecoder {

    private static final int PRELUDE_LENGTH = 8;
    private static final int PRELUDE_CRC_LENGTH = 4;
    private static final int MESSAGE_CRC_LENGTH = 4;
    private static final int MIN_MESSAGE_LENGTH = PRELUDE_LENGTH + PRELUDE_CRC_LENGTH + MESSAGE_CRC_LENGTH;

    private final ObjectMapper objectMapper;
    private byte[] pending = new byte[0];

    public BedrockEventStreamDecoder() {
        this(new ObjectMapper());
    }

    public BedrockEventStreamDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<BedrockEvent> append(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        return append(bytes, 0, bytes.length);
    }

    public List<BedrockEvent> append(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null || length <= 0) {
            return List.of();
        }
        byte[] merged = new byte[pending.length + length];
        System.arraycopy(pending, 0, merged, 0, pending.length);
        System.arraycopy(bytes, offset, merged, pending.length, length);
        pending = merged;

        List<BedrockEvent> decoded = new ArrayList<>();
        int cursor = 0;
        while (pending.length - cursor >= PRELUDE_LENGTH + PRELUDE_CRC_LENGTH) {
            int totalLength = readInt(pending, cursor);
            int headersLength = readInt(pending, cursor + 4);
            if (totalLength < MIN_MESSAGE_LENGTH) {
                throw new BedrockEventStreamException("EventStream message too short: " + totalLength);
            }
            if (headersLength < 0 || headersLength > totalLength - MIN_MESSAGE_LENGTH) {
                throw new BedrockEventStreamException("Invalid EventStream headers length: " + headersLength);
            }
            if (pending.length - cursor < totalLength) {
                break;
            }
            validatePreludeCrc(pending, cursor);
            validateMessageCrc(pending, cursor, totalLength);

            EventStreamMessage message = decodeMessage(pending, cursor, totalLength, headersLength);
            decoded.add(classify(message));
            cursor += totalLength;
        }

        if (cursor == 0) {
            return decoded;
        }
        pending = Arrays.copyOfRange(pending, cursor, pending.length);
        return decoded;
    }

    public void finish() throws IOException {
        if (pending.length != 0) {
            throw new BedrockEventStreamException("Trailing incomplete EventStream payload: " + pending.length + " bytes");
        }
    }

    private void validatePreludeCrc(byte[] bytes, int offset) throws IOException {
        long expected = Integer.toUnsignedLong(readInt(bytes, offset + PRELUDE_LENGTH));
        long actual = crc32(bytes, offset, PRELUDE_LENGTH);
        if (expected != actual) {
            throw new BedrockEventStreamException("EventStream prelude CRC mismatch");
        }
    }

    private void validateMessageCrc(byte[] bytes, int offset, int totalLength) throws IOException {
        int crcOffset = offset + totalLength - MESSAGE_CRC_LENGTH;
        long expected = Integer.toUnsignedLong(readInt(bytes, crcOffset));
        long actual = crc32(bytes, offset, totalLength - MESSAGE_CRC_LENGTH);
        if (expected != actual) {
            throw new BedrockEventStreamException("EventStream message CRC mismatch");
        }
    }

    private EventStreamMessage decodeMessage(byte[] bytes, int offset, int totalLength, int headersLength) throws IOException {
        int headerOffset = offset + PRELUDE_LENGTH + PRELUDE_CRC_LENGTH;
        Map<String, HeaderValue> headers = decodeHeaders(bytes, headerOffset, headersLength);
        int payloadOffset = headerOffset + headersLength;
        int payloadLength = totalLength - headersLength - MIN_MESSAGE_LENGTH;
        byte[] payload = Arrays.copyOfRange(bytes, payloadOffset, payloadOffset + payloadLength);

        String messageType = stringHeader(headers, ":message-type");
        String eventType = stringHeader(headers, ":event-type");
        String contentType = stringHeader(headers, ":content-type");
        String exceptionType = stringHeader(headers, ":exception-type");

        return new EventStreamMessage(headers, payload, messageType, eventType, contentType, exceptionType);
    }

    private Map<String, HeaderValue> decodeHeaders(byte[] bytes, int offset, int headersLength) throws IOException {
        Map<String, HeaderValue> headers = new LinkedHashMap<>();
        int cursor = offset;
        int end = offset + headersLength;
        while (cursor < end) {
            int nameLength = Byte.toUnsignedInt(bytes[cursor++]);
            if (cursor + nameLength + 1 > end) {
                throw new BedrockEventStreamException("Truncated EventStream header");
            }
            String name = new String(bytes, cursor, nameLength, StandardCharsets.UTF_8);
            cursor += nameLength;
            HeaderType type = HeaderType.fromWireValue(Byte.toUnsignedInt(bytes[cursor++]));
            DecodedHeaderValue decoded = decodeHeaderValue(type, bytes, cursor, end);
            cursor = decoded.nextOffset();
            headers.put(name, new HeaderValue(type, decoded.value()));
        }
        if (cursor != end) {
            throw new BedrockEventStreamException("EventStream headers length mismatch");
        }
        return headers;
    }

    private DecodedHeaderValue decodeHeaderValue(HeaderType type, byte[] bytes, int offset, int limit) throws IOException {
        return switch (type) {
            case BOOL_TRUE -> new DecodedHeaderValue(Boolean.TRUE, offset);
            case BOOL_FALSE -> new DecodedHeaderValue(Boolean.FALSE, offset);
            case BYTE -> ensure(offset + 1 <= limit, "Truncated byte header value", new DecodedHeaderValue(bytes[offset], offset + 1));
            case SHORT -> ensure(offset + 2 <= limit, "Truncated short header value",
                    new DecodedHeaderValue(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort(), offset + 2));
            case INTEGER -> ensure(offset + 4 <= limit, "Truncated int header value",
                    new DecodedHeaderValue(readInt(bytes, offset), offset + 4));
            case LONG -> ensure(offset + 8 <= limit, "Truncated long header value",
                    new DecodedHeaderValue(ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong(), offset + 8));
            case BYTE_ARRAY -> decodeVariableBytes(bytes, offset, limit);
            case STRING -> decodeVariableString(bytes, offset, limit);
            case TIMESTAMP -> ensure(offset + 8 <= limit, "Truncated timestamp header value",
                    new DecodedHeaderValue(ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong(), offset + 8));
            case UUID -> ensure(offset + 16 <= limit, "Truncated UUID header value", decodeUuid(bytes, offset));
        };
    }

    private DecodedHeaderValue decodeVariableBytes(byte[] bytes, int offset, int limit) throws IOException {
        ensure(offset + 2 <= limit, "Truncated byte-array header length", null);
        int length = Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort());
        int valueOffset = offset + 2;
        ensure(valueOffset + length <= limit, "Truncated byte-array header value", null);
        return new DecodedHeaderValue(Arrays.copyOfRange(bytes, valueOffset, valueOffset + length), valueOffset + length);
    }

    private DecodedHeaderValue decodeVariableString(byte[] bytes, int offset, int limit) throws IOException {
        ensure(offset + 2 <= limit, "Truncated string header length", null);
        int length = Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort());
        int valueOffset = offset + 2;
        ensure(valueOffset + length <= limit, "Truncated string header value", null);
        return new DecodedHeaderValue(new String(bytes, valueOffset, length, StandardCharsets.UTF_8), valueOffset + length);
    }

    private DecodedHeaderValue decodeUuid(byte[] bytes, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.BIG_ENDIAN);
        return new DecodedHeaderValue(new UUID(buffer.getLong(), buffer.getLong()), offset + 16);
    }

    private BedrockEvent classify(EventStreamMessage message) throws IOException {
        JsonNode payloadJson = parseJson(message.payload(), message.contentType());
        if (payloadJson != null && payloadJson.isObject()) {
            JsonNode node = payloadJson;
            if (node.has("chunk")) {
                JsonNode bytesNode = node.path("chunk").get("bytes");
                byte[] chunkBytes = decodeChunkBytes(bytesNode);
                JsonNode chunkJson = parseJson(chunkBytes, "application/json");
                return new BedrockEvent(BedrockEventType.CHUNK, message, payloadJson, chunkBytes, chunkJson, "", null);
            }
            String wrapperExceptionType = firstKnownExceptionKey(node);
            if (!wrapperExceptionType.isEmpty()) {
                JsonNode details = node.path(wrapperExceptionType);
                return new BedrockEvent(BedrockEventType.EXCEPTION, message, payloadJson, new byte[0], null, wrapperExceptionType, details);
            }
        }
        if (!message.exceptionType().isBlank() || "exception".equalsIgnoreCase(message.messageType())) {
            String type = !message.exceptionType().isBlank() ? message.exceptionType() : message.eventType();
            return new BedrockEvent(BedrockEventType.EXCEPTION, message, payloadJson, new byte[0], null, type, payloadJson);
        }
        return new BedrockEvent(BedrockEventType.UNKNOWN, message, payloadJson, new byte[0], null, "", null);
    }

    private JsonNode parseJson(byte[] bytes, String contentType) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        boolean looksLikeJson = contentType != null && contentType.toLowerCase().contains("json");
        if (!looksLikeJson) {
            int first = firstNonWhitespace(bytes);
            looksLikeJson = first >= 0 && (bytes[first] == '{' || bytes[first] == '[' || bytes[first] == '"');
        }
        if (!looksLikeJson) {
            return null;
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException ex) {
            return null;
        }
    }

    private int firstNonWhitespace(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (!Character.isWhitespace((char) bytes[i])) {
                return i;
            }
        }
        return -1;
    }

    private byte[] decodeChunkBytes(JsonNode bytesNode) throws IOException {
        if (bytesNode == null || bytesNode.isNull()) {
            return new byte[0];
        }
        try {
            return bytesNode.binaryValue();
        } catch (IOException ignored) {
        }
        if (bytesNode.isTextual()) {
            try {
                return Base64.getDecoder().decode(bytesNode.asText());
            } catch (IllegalArgumentException ex) {
                return bytesNode.asText().getBytes(StandardCharsets.UTF_8);
            }
        }
        if (bytesNode.isBinary()) {
            return bytesNode.binaryValue();
        }
        throw new BedrockEventStreamException("Unsupported chunk.bytes payload");
    }

    private String firstKnownExceptionKey(JsonNode node) {
        for (String key : List.of(
                "internalServerException",
                "modelStreamErrorException",
                "modelTimeoutException",
                "serviceUnavailableException",
                "throttlingException",
                "validationException"
        )) {
            if (node.has(key)) {
                return key;
            }
        }
        return "";
    }

    private String stringHeader(Map<String, HeaderValue> headers, String name) {
        HeaderValue value = headers.get(name);
        return value == null ? "" : value.asString();
    }

    private static int readInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static long crc32(byte[] bytes, int offset, int length) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, offset, length);
        return crc32.getValue();
    }

    private <T> T ensure(boolean condition, String message, T value) throws IOException {
        if (!condition) {
            throw new BedrockEventStreamException(message);
        }
        return value;
    }

    public enum BedrockEventType {
        CHUNK,
        EXCEPTION,
        UNKNOWN
    }

    public enum HeaderType {
        BOOL_TRUE(0),
        BOOL_FALSE(1),
        BYTE(2),
        SHORT(3),
        INTEGER(4),
        LONG(5),
        BYTE_ARRAY(6),
        STRING(7),
        TIMESTAMP(8),
        UUID(9);

        private final int wireValue;

        HeaderType(int wireValue) {
            this.wireValue = wireValue;
        }

        public static HeaderType fromWireValue(int wireValue) throws IOException {
            for (HeaderType value : values()) {
                if (value.wireValue == wireValue) {
                    return value;
                }
            }
            throw new BedrockEventStreamException("Unsupported EventStream header type: " + wireValue);
        }
    }

    public record HeaderValue(HeaderType type, Object value) {
        public String asString() {
            return value instanceof String string ? string : "";
        }
    }

    public record EventStreamMessage(
            Map<String, HeaderValue> headers,
            byte[] payload,
            String messageType,
            String eventType,
            String contentType,
            String exceptionType
    ) {
    }

    public record BedrockEvent(
            BedrockEventType type,
            EventStreamMessage message,
            JsonNode payload,
            byte[] chunkBytes,
            JsonNode chunkJson,
            String exceptionType,
            JsonNode exceptionPayload
    ) {
        public boolean isChunk() {
            return type == BedrockEventType.CHUNK;
        }

        public boolean isException() {
            return type == BedrockEventType.EXCEPTION;
        }
    }

    private record DecodedHeaderValue(Object value, int nextOffset) {
    }

    public static final class BedrockEventStreamException extends IOException {
        public BedrockEventStreamException(String message) {
            super(message);
        }
    }
}
