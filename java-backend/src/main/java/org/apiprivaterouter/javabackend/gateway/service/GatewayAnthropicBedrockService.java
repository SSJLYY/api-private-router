package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GatewayAnthropicBedrockService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final BedrockRequestSigner requestSigner;
    private final BedrockModelResolver modelResolver;
    private final BedrockRequestBodyTransformer requestBodyTransformer;
    private final AdminProxyRepository proxyRepository;
    private final ObjectMapper objectMapper;

    public GatewayAnthropicBedrockService(
            BedrockRequestSigner requestSigner,
            BedrockModelResolver modelResolver,
            BedrockRequestBodyTransformer requestBodyTransformer,
            AdminProxyRepository proxyRepository,
            ObjectMapper objectMapper
    ) {
        this.requestSigner = requestSigner;
        this.modelResolver = modelResolver;
        this.requestBodyTransformer = requestBodyTransformer;
        this.proxyRepository = proxyRepository;
        this.objectMapper = objectMapper;
    }

    public boolean canHandle(AdminAccountResponse account) {
        return account != null
                && "anthropic".equalsIgnoreCase(account.platform())
                && "bedrock".equalsIgnoreCase(account.type());
    }

    public PreparedBedrockRequest prepareMessagesRequest(AdminAccountResponse account, byte[] requestBody, String betaHeader) {
        ObjectNode raw = parseRequestBody(requestBody);
        String requestedModel = requireModel(raw);
        boolean stream = raw.path("stream").asBoolean(false);
        String modelId = modelResolver.resolveModelId(account, requestedModel);
        if (modelId == null || modelId.isBlank()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "unsupported bedrock model: " + requestedModel);
        }
        List<String> betaTokens = requestBodyTransformer.resolveBetaTokens(betaHeader, raw, modelId);
        ObjectNode transformed = requestBodyTransformer.transformWithResolvedTokens(raw, modelId, betaTokens);
        String region = requestSigner.resolveRegion(account);
        String url = modelResolver.buildBedrockUrl(region, modelId, stream);
        return new PreparedBedrockRequest(
                requestedModel,
                modelId,
                region,
                stream,
                betaTokens,
                writeJson(transformed),
                URI.create(url)
        );
    }

    public HttpRequest buildRequest(AdminAccountResponse account, PreparedBedrockRequest prepared) {
        if (prepared == null) {
            throw new AnthropicApiErrorException(500, "api_error", "Bedrock request was not prepared");
        }
        return buildRequest(account, prepared.uri(), prepared.requestBody());
    }

    public HttpRequest buildRequest(
            AdminAccountResponse account,
            URI uri,
            byte[] requestBody
    ) {
        String region = requestSigner.resolveRegion(account);
        if (requestSigner.isApiKeyMode(account)) {
            return requestSigner.buildApiKeyRequest(uri, requestBody, account);
        }
        return requestSigner.buildSignedRequest(uri, requestBody, region, account);
    }

    public HttpResponse<InputStream> send(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnthropicApiErrorException(502, "api_error", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "api_error", "Upstream request failed");
        }
    }

    public void writeMessagesResponse(HttpServletResponse response, HttpResponse<InputStream> upstream, boolean requestedStream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status == 429 || status == 529) {
            throw new org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException(
                    status,
                    "rate_limit_error",
                    readUpstreamMessage(upstream, "Bedrock upstream request failed")
            ).toAnthropicApiErrorException();
        }
        if (status >= 400) {
            throw translateAnthropicError(upstream);
        }

        copyResponseHeaders(response, upstream, requestedStream ? "text/event-stream" : "application/json");
        response.setStatus(200);
        if (requestedStream) {
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            writeStreamingMessagesResponse(response, upstream);
            return;
        }
        writeBufferedMessagesResponse(response, upstream);
    }

    public ObjectNode readResponsesPayload(HttpResponse<InputStream> upstream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status == 429 || status == 529) {
            throw new org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException(
                    status,
                    "rate_limit_error",
                    readUpstreamMessage(upstream, "Bedrock upstream request failed")
            );
        }
        if (status >= 400) {
            throw translateOpenAiError(upstream);
        }
        try (InputStream input = upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            if (body.length == 0) {
                throw new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", "Bedrock upstream response was empty");
            }
            if (isEventStream(upstream)) {
                return readStreamingResponsesPayload(body);
            }
            return normalizeNonStreamingResponse(body);
        } catch (IOException ex) {
            throw new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", "Failed to read Bedrock upstream response");
        }
    }

    public void writeResponsesStream(HttpServletResponse response, HttpResponse<InputStream> upstream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status == 429 || status == 529) {
            throw new org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException(
                    status,
                    "rate_limit_error",
                    readUpstreamMessage(upstream, "Bedrock upstream request failed")
            );
        }
        if (status >= 400) {
            throw translateOpenAiError(upstream);
        }

        copyResponseHeaders(response, upstream, "text/event-stream");
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        try (InputStream input = upstream.body()) {
            if (input == null) {
                throw new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", "Bedrock upstream response was empty");
            }
            byte[] bytes = input.readAllBytes();
            if (isEventStream(upstream)) {
                List<String> sseEvents = new BedrockResponseRelay(objectMapper).toSseEvents(
                        decodeEventStream(bytes)
                );
                ServletOutputStream output = response.getOutputStream();
                for (String event : sseEvents) {
                    output.write(event.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                response.flushBuffer();
                return;
            }
            ObjectNode normalized = normalizeNonStreamingResponse(bytes);
            String payload = "event: response.completed\n" +
                    "data: " + objectMapper.writeValueAsString(normalized) + "\n\n";
            ServletOutputStream output = response.getOutputStream();
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.flush();
            response.flushBuffer();
        } catch (AnthropicApiErrorException | org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", "Failed to stream Bedrock responses payload");
        }
    }

    public String resolveRegion(AdminAccountResponse account) {
        return requestSigner.resolveRegion(account);
    }

    private ObjectNode parseRequestBody(byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(requestBody);
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
        } catch (IOException ignored) {
        }
        throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
    }

    private String requireModel(ObjectNode body) {
        String model = text(body == null ? null : body.get("model"));
        if (model.isEmpty()) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
        }
        return model;
    }

    private byte[] writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(500, "api_error", "Failed to encode Bedrock request body");
        }
    }

    private void writeStreamingMessagesResponse(HttpServletResponse response, HttpResponse<InputStream> upstream) {
        try (InputStream input = upstream.body()) {
            if (input == null) {
                throw new AnthropicApiErrorException(502, "api_error", "Bedrock upstream response was empty");
            }
            byte[] bytes = input.readAllBytes();
            List<String> events = new BedrockResponseRelay(objectMapper).toSseEvents(decodeEventStream(bytes));
            ServletOutputStream output = response.getOutputStream();
            for (String event : events) {
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            response.flushBuffer();
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "api_error", "Failed to stream Bedrock upstream response");
        }
    }

    private void writeBufferedMessagesResponse(HttpServletResponse response, HttpResponse<InputStream> upstream) {
        try (InputStream input = upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            if (body.length == 0) {
                throw new AnthropicApiErrorException(502, "api_error", "Bedrock upstream response was empty");
            }
            ObjectNode normalized = normalizeNonStreamingResponse(body);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(normalized));
            response.flushBuffer();
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AnthropicApiErrorException(502, "api_error", "Failed to write Bedrock upstream response");
        }
    }

    private ObjectNode normalizeNonStreamingResponse(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", "Bedrock upstream returned invalid JSON");
            }
            ObjectNode normalized = objectNode.deepCopy();
            JsonNode metrics = normalized.remove("amazon-bedrock-invocationMetrics");
            if ((normalized.get("usage") == null || normalized.get("usage").isNull()) && metrics instanceof ObjectNode metricsNode) {
                ObjectNode usage = normalized.putObject("usage");
                usage.put("input_tokens", metricsNode.path("inputTokenCount").asInt(0));
                usage.put("output_tokens", metricsNode.path("outputTokenCount").asInt(0));
            }
            return normalized;
        } catch (IOException ex) {
            throw new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", "Bedrock upstream returned invalid JSON");
        }
    }

    private ObjectNode readStreamingResponsesPayload(byte[] bytes) throws IOException {
        return new BedrockResponseRelay(objectMapper).toNormalizedJson(decodeEventStream(bytes));
    }

    private List<BedrockEventStreamDecoder.BedrockEvent> decodeEventStream(byte[] bytes) throws IOException {
        BedrockEventStreamDecoder decoder = new BedrockEventStreamDecoder(objectMapper);
        List<BedrockEventStreamDecoder.BedrockEvent> events = decoder.append(bytes);
        decoder.finish();
        return events;
    }

    private boolean isEventStream(HttpResponse<InputStream> upstream) {
        String contentType = upstream == null
                ? ""
                : upstream.headers().firstValue("content-type").orElse("");
        return contentType.toLowerCase(Locale.ROOT).contains("application/vnd.amazon.eventstream");
    }

    private void copyResponseHeaders(HttpServletResponse response, HttpResponse<InputStream> upstream, String fallbackContentType) {
        if (upstream == null) {
            return;
        }
        upstream.headers().map().forEach((name, values) -> {
            if (name == null) {
                return;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if ("connection".equals(lower)
                    || "keep-alive".equals(lower)
                    || "proxy-authenticate".equals(lower)
                    || "proxy-authorization".equals(lower)
                    || "te".equals(lower)
                    || "trailers".equals(lower)
                    || "transfer-encoding".equals(lower)
                    || "upgrade".equals(lower)
                    || "content-length".equals(lower)
                    || "content-type".equals(lower)) {
                return;
            }
            if ("x-amzn-requestid".equals(lower)) {
                for (String value : values) {
                    response.addHeader("x-request-id", value);
                }
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        if (response.getContentType() == null) {
            response.setContentType(fallbackContentType);
        }
    }

    private AnthropicApiErrorException translateAnthropicError(HttpResponse<InputStream> upstream) {
        BufferedError error = readBufferedError(upstream);
        if (error.statusCode() >= 500) {
            return new AnthropicApiErrorException(502, "api_error", error.message());
        }
        if (error.statusCode() == 401 || error.statusCode() == 403) {
            return new AnthropicApiErrorException(error.statusCode(), "permission_error", error.message());
        }
        if ("not_found_error".equals(error.errorType())) {
            return new AnthropicApiErrorException(error.statusCode(), "not_found_error", error.message());
        }
        return new AnthropicApiErrorException(error.statusCode() <= 0 ? 502 : error.statusCode(), "invalid_request_error", error.message());
    }

    private org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException translateOpenAiError(HttpResponse<InputStream> upstream) {
        BufferedError error = readBufferedError(upstream);
        if (error.statusCode() >= 500) {
            return new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(502, "server_error", error.message());
        }
        if (error.statusCode() == 401 || error.statusCode() == 403) {
            return new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(error.statusCode(), "permission_error", error.message());
        }
        if ("not_found_error".equals(error.errorType())) {
            return new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(error.statusCode(), "not_found_error", error.message());
        }
        return new org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException(error.statusCode() <= 0 ? 502 : error.statusCode(), "invalid_request_error", error.message());
    }

    private BufferedError readBufferedError(HttpResponse<InputStream> upstream) {
        byte[] body = new byte[0];
        try (InputStream input = upstream == null ? null : upstream.body()) {
            body = input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException ignored) {
        }
        return new BufferedError(
                upstream == null ? 0 : upstream.statusCode(),
                extractErrorType(body),
                extractMessage(body, "Bedrock upstream request failed")
        );
    }

    private String readUpstreamMessage(HttpResponse<InputStream> upstream, String fallback) {
        try (InputStream input = upstream == null ? null : upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            return extractMessage(body, fallback);
        } catch (IOException ignored) {
            return fallback;
        }
    }

    private String extractErrorType(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error instanceof ObjectNode errorNode) {
                return text(errorNode.get("type"));
            }
            for (String field : List.of("validationException", "modelStreamErrorException", "throttlingException", "serviceUnavailableException")) {
                if (node != null && node.has(field)) {
                    return field;
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private String extractMessage(byte[] body, String fallback) {
        if (body == null || body.length == 0) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node != null) {
                JsonNode error = node.get("error");
                if (error instanceof ObjectNode errorNode) {
                    String message = text(errorNode.get("message"));
                    if (!message.isEmpty()) {
                        return message;
                    }
                }
                String direct = text(node.get("message"));
                if (!direct.isEmpty()) {
                    return direct;
                }
                for (String field : List.of("validationException", "modelStreamErrorException", "throttlingException", "serviceUnavailableException")) {
                    JsonNode wrapped = node.get(field);
                    if (wrapped != null && !wrapped.isNull()) {
                        String wrappedMessage = text(wrapped.get("message"));
                        if (!wrappedMessage.isEmpty()) {
                            return wrappedMessage;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        return raw.isEmpty() ? fallback : raw;
    }

    private HttpClient buildHttpClient(AdminAccountResponse account) {
        AdminProxyResponse proxy = account == null || account.proxy_id() == null || account.proxy_id() <= 0
                ? null
                : proxyRepository.getProxy(account.proxy_id()).orElse(null);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy == null || proxy.host() == null || proxy.host().isBlank() || proxy.port() <= 0) {
            return builder.build();
        }
        Proxy.Type type = proxy.protocol() != null && proxy.protocol().toLowerCase(Locale.ROOT).startsWith("socks")
                ? Proxy.Type.SOCKS
                : Proxy.Type.HTTP;
        builder.proxy(new FixedProxySelector(type, proxy.host(), proxy.port()));
        if (proxy.username() != null && !proxy.username().isBlank()) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            proxy.username(),
                            (proxy.password() == null ? "" : proxy.password()).toCharArray()
                    );
                }
            });
        }
        return builder.build();
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    public record PreparedBedrockRequest(
            String requestedModel,
            String modelId,
            String region,
            boolean stream,
            List<String> betaTokens,
            byte[] requestBody,
            URI uri
    ) {
    }

    private record BufferedError(int statusCode, String errorType, String message) {
    }

    private static final class FixedProxySelector extends ProxySelector {
        private final List<Proxy> proxies;

        private FixedProxySelector(Proxy.Type type, String host, int port) {
            this.proxies = List.of(new Proxy(type, new InetSocketAddress(host, port)));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return proxies;
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
        }
    }
}
