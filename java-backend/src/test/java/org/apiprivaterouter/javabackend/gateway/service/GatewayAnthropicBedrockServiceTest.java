package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAnthropicBedrockServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messagesServiceRoutesBedrockRequestToBedrockUpstream() {
        AdminAccountResponse account = account("anthropic", "bedrock", Map.of(
                "auth_mode", "apikey",
                "api_key", "bedrock-key",
                "aws_region", "us-east-1"
        ));
        GatewayAnthropicBedrockService bedrockService = testBedrockService();
        AdminAccountRepository accountRepository = mock(AdminAccountRepository.class);
        when(accountRepository.getAccount(1L)).thenReturn(Optional.of(account));

        GatewayAnthropicMessagesService service = new GatewayAnthropicMessagesService(
                accountRepository,
                mock(AdminProxyRepository.class),
                bedrockService,
                objectMapper,
                null
        );
        GatewayRuntimeContext runtimeContext = new GatewayRuntimeContext(
                null,
                null,
                null,
                new GatewayAccountSummary(1L, "bedrock", "anthropic", "bedrock", "active", 1, null, Map.of(), Map.of())
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("anthropic-beta", "context-1m-2025-08-07");
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] body = """
                {"model":"claude-sonnet-4-5","stream":false,"messages":[{"role":"user","content":"hello"}]}
                """.getBytes(StandardCharsets.UTF_8);

        service.forward(runtimeContext, request, response, body);

        assertEquals(200, response.getStatus());
        ObjectNode payload = readJson(response.getContentAsByteArray());
        assertEquals("msg_1", payload.path("id").asText());
        assertEquals(7, payload.path("usage").path("output_tokens").asInt());
    }

    @Test
    void responsesServiceConvertsBedrockStreamIntoOpenAiResponsesPayload() {
        AdminAccountResponse account = account("anthropic", "bedrock", Map.of(
                "auth_mode", "apikey",
                "api_key", "bedrock-key",
                "aws_region", "us-east-1"
        ));
        GatewayAnthropicBedrockService bedrockService = testBedrockService();
        AdminAccountRepository accountRepository = mock(AdminAccountRepository.class);
        when(accountRepository.getAccount(1L)).thenReturn(Optional.of(account));

        GatewayAnthropicMessagesService messagesService = new GatewayAnthropicMessagesService(
                accountRepository,
                mock(AdminProxyRepository.class),
                bedrockService,
                objectMapper,
                null
        );
        GatewayAnthropicResponsesService responsesService = new GatewayAnthropicResponsesService(
                accountRepository,
                messagesService,
                bedrockService,
                objectMapper
        );
        GatewayRuntimeContext runtimeContext = new GatewayRuntimeContext(
                null,
                null,
                null,
                new GatewayAccountSummary(1L, "bedrock", "anthropic", "bedrock", "active", 1, null, Map.of(), Map.of())
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] body = """
                {
                  "model":"claude-sonnet-4-5",
                  "stream":false,
                  "input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]}]
                }
                """.getBytes(StandardCharsets.UTF_8);

        responsesService.forward(runtimeContext, request, response, body);

        ObjectNode payload = readJson(response.getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals("response", payload.path("object").asText());
        assertEquals("claude-sonnet-4-5", payload.path("model").asText());
        assertEquals("completed", payload.path("status").asText());
        assertEquals("Hello from Bedrock", payload.path("output").get(0).path("content").get(0).path("text").asText());
    }

    @Test
    void countTokensRejectsBedrockAccounts() {
        AdminAccountResponse account = account("anthropic", "bedrock", Map.of(
                "auth_mode", "apikey",
                "api_key", "bedrock-key"
        ));
        AdminAccountRepository accountRepository = mock(AdminAccountRepository.class);
        when(accountRepository.getAccount(1L)).thenReturn(Optional.of(account));

        GatewayCountTokensService service = new GatewayCountTokensService(
                accountRepository,
                mock(AdminProxyRepository.class),
                objectMapper,
                null
        );
        GatewayRuntimeContext runtimeContext = new GatewayRuntimeContext(
                null,
                null,
                null,
                new GatewayAccountSummary(1L, "bedrock", "anthropic", "bedrock", "active", 1, null, Map.of(), Map.of())
        );

        AnthropicApiErrorException ex = assertThrows(
                AnthropicApiErrorException.class,
                () -> service.countTokens(runtimeContext, "{\"model\":\"claude-sonnet-4-5\"}".getBytes(StandardCharsets.UTF_8), "/v1/messages/count_tokens")
        );

        assertEquals(404, ex.getStatus());
        assertEquals("not_found_error", ex.getErrorType());
    }

    private GatewayAnthropicBedrockService testBedrockService() {
        return new GatewayAnthropicBedrockService(
                new BedrockRequestSigner(),
                new BedrockModelResolver(),
                new BedrockRequestBodyTransformer(objectMapper),
                mock(AdminProxyRepository.class),
                objectMapper
        ) {
            @Override
            public HttpResponse<InputStream> send(AdminAccountResponse account, HttpRequest request) {
                String path = request.uri().getPath();
                if (path.endsWith("/invoke")) {
                    return response(
                            200,
                            Map.of("content-type", List.of("application/json")),
                            """
                                    {
                                      "id":"msg_1",
                                      "type":"message",
                                      "role":"assistant",
                                      "model":"us.anthropic.claude-sonnet-4-5-20250929-v1:0",
                                      "content":[{"type":"text","text":"Hello from Bedrock"}],
                                      "stop_reason":"end_turn",
                                      "stop_sequence":null,
                                      "amazon-bedrock-invocationMetrics":{"inputTokenCount":12,"outputTokenCount":7}
                                    }
                                    """.getBytes(StandardCharsets.UTF_8)
                    );
                }
                if (path.endsWith("/invoke-with-response-stream")) {
                    return response(
                            200,
                            Map.of("content-type", List.of("application/vnd.amazon.eventstream")),
                            encodeChunkStream()
                    );
                }
                throw new IllegalStateException("unexpected request path: " + path);
            }
        };
    }

    private byte[] encodeChunkStream() {
        try {
            String messageStart = """
                    {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"us.anthropic.claude-sonnet-4-5-20250929-v1:0","usage":{"input_tokens":12}}}
                    """;
            String blockStart = """
                    {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                    """;
            String blockDelta = """
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello from Bedrock"}}
                    """;
            String messageDelta = """
                    {"type":"message_delta","delta":{"stop_reason":"end_turn"},"amazon-bedrock-invocationMetrics":{"inputTokenCount":12,"outputTokenCount":7}}
                    """;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(encodeChunkEvent(messageStart));
            out.write(encodeChunkEvent(blockStart));
            out.write(encodeChunkEvent(blockDelta));
            out.write(encodeChunkEvent(messageDelta));
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] encodeChunkEvent(String anthropicEventJson) throws Exception {
        String payloadJson = """
                {"chunk":{"bytes":"%s"}}
                """.formatted(Base64.getEncoder().encodeToString(anthropicEventJson.getBytes(StandardCharsets.UTF_8)));
        return encodeMessage(
                Map.of(
                        ":message-type", "event",
                        ":event-type", "chunk",
                        ":content-type", "application/json"
                ),
                payloadJson.getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] encodeMessage(Map<String, String> headers, byte[] payload) throws Exception {
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

    private HttpResponse<InputStream> response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder().uri(URI.create("https://bedrock-runtime.us-east-1.amazonaws.com")).build();
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (a, b) -> true);
            }

            @Override
            public InputStream body() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public Optional<javax.net.ssl.SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://bedrock-runtime.us-east-1.amazonaws.com");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private ObjectNode readJson(byte[] bytes) {
        try {
            return (ObjectNode) objectMapper.readTree(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] intBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    private long crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }

    private AdminAccountResponse account(String platform, String type, Map<String, Object> credentials) {
        try {
            RecordComponent[] components = AdminAccountResponse.class.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                args[i] = defaultValue(component.getType());
                switch (component.getName()) {
                    case "id" -> args[i] = 1L;
                    case "name" -> args[i] = "acc";
                    case "platform" -> args[i] = platform;
                    case "type" -> args[i] = type;
                    case "credentials" -> args[i] = credentials;
                    case "extra" -> args[i] = Map.of();
                    case "concurrency" -> args[i] = 1;
                    case "priority" -> args[i] = 1;
                    case "rate_multiplier" -> args[i] = 1.0d;
                    case "status" -> args[i] = "active";
                    case "group_ids" -> args[i] = List.<Long>of();
                    case "groups" -> args[i] = List.of();
                    default -> {
                    }
                }
            }
            Constructor<AdminAccountResponse> constructor = AdminAccountResponse.class.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(args);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        throw new IllegalArgumentException("unsupported primitive type: " + type);
    }
}
