package org.apiprivaterouter.javabackend.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.fileupload.FileItemIterator;
import org.apache.tomcat.util.http.fileupload.FileItemStream;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.UploadContext;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class GatewayOpenAiImagesService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com";
    private static final int OPENAI_IMAGE_MAX_UPLOAD_PART_SIZE = 20 << 20;
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept",
            "content-type",
            "openai-organization",
            "openai-project",
            "user-agent"
    );
    private static final Set<String> COMPAT_REQUEST_HEADER_ALLOWLIST = Set.of(
            "accept-language",
            "openai-beta",
            "user-agent"
    );
    private static final Set<String> RESPONSE_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "content-length"
    );

    private final AdminAccountRepository accountRepository;
    private final AdminProxyRepository proxyRepository;
    private final GatewayOpenAiResponsesService responsesService;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public GatewayOpenAiImagesService(
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            GatewayOpenAiResponsesService responsesService,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.responsesService = responsesService;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
    }

    public void forward(GatewayRuntimeContext runtimeContext, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        if (runtimeContext.apiKey().group() == null || !runtimeContext.apiKey().group().allowImageGeneration()) {
            throw new OpenAiApiErrorException(403, "permission_error", "Image generation is not enabled for this group");
        }
        if (runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible accounts");
        }
        AdminAccountResponse account = accountRepository.getAccount(runtimeContext.account().id())
                .orElseThrow(() -> new OpenAiApiErrorException(503, "api_error", "No available compatible accounts"));
        if (!"openai".equalsIgnoreCase(account.platform())) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible OpenAI accounts");
        }
        String type = normalize(account.type());
        if (isOauthLikeType(type)) {
            forwardOauth(account, request, response, body);
            return;
        }
        if ("apikey".equals(type)) {
            forwardApiKey(account, request, response, body);
            return;
        }
        throw new OpenAiApiErrorException(501, "unsupported_error", "OpenAI image forwarding is not supported for this account type yet");
    }

    private void forwardApiKey(AdminAccountResponse account, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        String endpoint = resolveEndpoint(request);
        String contentType = defaultString(request.getContentType());
        byte[] forwardBody = rewriteModelIfJson(body, contentType, account);
        HttpRequest upstreamRequest = buildApiKeyRequest(account, request, endpoint, contentType, forwardBody);
        HttpResponse<byte[]> upstream = sendBytes(account, upstreamRequest);
        throwIfFailoverRequiredBytes(upstream);
        writeByteResponse(response, upstream);
    }

    private void forwardOauth(AdminAccountResponse account, HttpServletRequest request, HttpServletResponse response, byte[] body) {
        String contentType = defaultString(request.getContentType()).toLowerCase(Locale.ROOT);
        ObjectNode imageRequest = contentType.contains("multipart/form-data")
                ? parseMultipartImageRequest(body, request)
                : parseImageRequest(body, request);
        String clientModel = resolveImageModel(imageRequest, account);
        ObjectNode responsesRequest = buildOauthImageResponsesRequest(imageRequest, clientModel, isEditRequest(request));
        byte[] responsesBody;
        try {
            responsesBody = objectMapper.writeValueAsBytes(responsesRequest);
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(500, "api_error", "Failed to encode image bridge request");
        }
        HttpRequest upstreamRequest = buildOauthRequest(account, request, responsesBody);
        HttpResponse<InputStream> upstream = sendStream(account, upstreamRequest);
        throwIfFailoverRequiredStream(upstream);
        if (upstream.statusCode() >= 400) {
            throw translateOpenAiError(upstream);
        }
        ObjectNode imageApiResponse = readOauthImageResponse(upstream, imageRequest.path("response_format").asText("b64_json"));
        response.setStatus(200);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            response.getWriter().write(objectMapper.writeValueAsString(imageApiResponse));
            response.flushBuffer();
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to write upstream response");
        }
    }

    private HttpRequest buildApiKeyRequest(AdminAccountResponse account, HttpServletRequest inbound, String endpoint, String contentType, byte[] body) {
        String apiKey = stringValue(account.credentials(), "api_key");
        if (apiKey == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No upstream credentials available");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(buildImagesUrl(account, endpoint)))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + apiKey);

        copyAllowedHeaders(inbound, builder, REQUEST_HEADER_ALLOWLIST);
        if (!contentType.isBlank()) {
            builder.setHeader("Content-Type", contentType);
        }
        applyCustomUserAgent(builder, account);
        return builder.build();
    }

    private HttpRequest buildOauthRequest(AdminAccountResponse account, HttpServletRequest inbound, byte[] body) {
        String accessToken = responsesService.stringValue(account.credentials(), "access_token");
        if (accessToken == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No upstream credentials available");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(responsesService.buildResponsesUrl(account, "")))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("OpenAI-Beta", "responses=experimental")
                .header("Originator", "opencode")
                .header("Host", "chatgpt.com");

        String chatgptAccountId = responsesService.stringValue(account.credentials(), "chatgpt_account_id");
        if (chatgptAccountId != null) {
            builder.header("chatgpt-account-id", chatgptAccountId);
        }
        copyAllowedHeaders(inbound, builder, COMPAT_REQUEST_HEADER_ALLOWLIST);
        applyCustomUserAgent(builder, account);
        return builder.build();
    }

    private ObjectNode parseImageRequest(byte[] body, HttpServletRequest request) {
        if (body == null || body.length == 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body must be a JSON object");
            }
            if (!objectNode.hasNonNull("prompt") || objectNode.path("prompt").asText("").isBlank()) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "prompt is required");
            }
            if (isEditRequest(request) && !hasImageInputs(objectNode)) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "image input is required");
            }
            return objectNode.deepCopy();
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private ObjectNode parseMultipartImageRequest(byte[] body, HttpServletRequest request) {
        if (body == null || body.length == 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        String contentType = defaultString(request == null ? null : request.getContentType());
        ObjectNode parsed = objectMapper.createObjectNode();
        ArrayNode images = objectMapper.createArrayNode();
        FileUpload upload = new FileUpload();
        try {
            FileItemIterator iterator = upload.getItemIterator(new ByteArrayUploadContext(body, contentType));
            while (iterator.hasNext()) {
                FileItemStream item = iterator.next();
                String fieldName = item.getFieldName() == null ? "" : item.getFieldName().trim();
                if (fieldName.isEmpty()) {
                    continue;
                }
                byte[] partBytes = readPartBytes(item);
                if (item.isFormField()) {
                    applyMultipartFormField(parsed, images, fieldName, new String(partBytes, StandardCharsets.UTF_8));
                    continue;
                }
                applyMultipartFileField(parsed, images, fieldName, item.getName(), item.getContentType(), partBytes);
            }
        } catch (FileUploadException ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse multipart request");
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to read multipart request");
        }
        if (!images.isEmpty()) {
            parsed.set("images", images);
        }
        if (!parsed.hasNonNull("prompt") || parsed.path("prompt").asText("").isBlank()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "prompt is required");
        }
        if (isEditRequest(request) && images.isEmpty()) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "image input is required");
        }
        return parsed;
    }

    private String resolveImageModel(ObjectNode request, AdminAccountResponse account) {
        String requestedModel = request.path("model").asText("gpt-image-2").trim();
        if (requestedModel.isBlank()) {
            requestedModel = "gpt-image-2";
        }
        if (!requestedModel.toLowerCase(Locale.ROOT).startsWith("gpt-image-")) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "images endpoint requires an image model, got \"" + requestedModel + "\"");
        }
        String mapped = resolveMappedModel(account, requestedModel);
        request.put("model", mapped);
        return mapped;
    }

    private ObjectNode buildOauthImageResponsesRequest(ObjectNode imageRequest, String toolModel, boolean edit) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("instructions", "");
        request.put("stream", false);
        ObjectNode reasoning = objectMapper.createObjectNode();
        reasoning.put("effort", "medium");
        reasoning.put("summary", "auto");
        request.set("reasoning", reasoning);
        request.put("parallel_tool_calls", true);
        ArrayNode include = objectMapper.createArrayNode();
        include.add("reasoning.encrypted_content");
        request.set("include", include);
        request.put("model", toolModel);
        request.put("store", false);

        ObjectNode toolChoice = objectMapper.createObjectNode();
        toolChoice.put("type", "image_generation");
        request.set("tool_choice", toolChoice);

        ArrayNode input = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("type", "input_text");
        textPart.put("text", imageRequest.path("prompt").asText(""));
        content.add(textPart);

        appendImageInputs(content, imageRequest.path("image"));
        appendImageInputs(content, imageRequest.path("images"));
        message.set("content", content);
        input.add(message);
        request.set("input", input);

        ArrayNode tools = objectMapper.createArrayNode();
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "image_generation");
        tool.put("action", edit ? "edit" : "generate");
        tool.put("model", toolModel);
        copyTextIfPresent(imageRequest, tool, "size");
        copyTextIfPresent(imageRequest, tool, "quality");
        copyTextIfPresent(imageRequest, tool, "background");
        copyTextIfPresent(imageRequest, tool, "output_format");
        copyTextIfPresent(imageRequest, tool, "moderation");
        copyTextIfPresent(imageRequest, tool, "input_fidelity");
        copyTextIfPresent(imageRequest, tool, "style");
        if (imageRequest.has("output_compression")) {
            tool.set("output_compression", imageRequest.get("output_compression"));
        }
        if (imageRequest.has("partial_images")) {
            tool.set("partial_images", imageRequest.get("partial_images"));
        }
        String normalizedMask = resolveMaskSource(imageRequest.get("mask"));
        if (!normalizedMask.isBlank()) {
            ObjectNode inputMask = objectMapper.createObjectNode();
            inputMask.put("image_url", normalizedMask);
            tool.set("input_image_mask", inputMask);
        }
        tools.add(tool);
        request.set("tools", tools);
        return request;
    }

    private void appendImageInputs(ArrayNode content, JsonNode imagesNode) {
        if (imagesNode == null || imagesNode.isNull()) {
            return;
        }
        if (imagesNode.isTextual()) {
            String imageValue = normalizeImageSource(imagesNode.asText());
            if (!imageValue.isBlank()) {
                ObjectNode part = objectMapper.createObjectNode();
                part.put("type", "input_image");
                part.put("image_url", imageValue);
                content.add(part);
            }
            return;
        }
        if (imagesNode.isObject()) {
            String imageValue = "";
            JsonNode imageUrl = imagesNode.get("image_url");
            if (imageUrl != null && imageUrl.isTextual()) {
                imageValue = normalizeImageSource(imageUrl.asText());
            }
            if (!imageValue.isBlank()) {
                ObjectNode part = objectMapper.createObjectNode();
                part.put("type", "input_image");
                part.put("image_url", imageValue);
                content.add(part);
            }
            return;
        }
        if (imagesNode instanceof ArrayNode items) {
            for (JsonNode item : items) {
                appendImageInputs(content, item);
            }
        }
    }

    private String normalizeImageSource(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.startsWith("data:")) {
            return trimmed;
        }
        if (trimmed.matches("^[A-Za-z0-9+/=\\r\\n]+$") && trimmed.length() > 32) {
            return "data:image/png;base64," + trimmed.replaceAll("\\s+", "");
        }
        return trimmed;
    }

    private String resolveMaskSource(JsonNode maskNode) {
        if (maskNode == null || maskNode.isNull()) {
            return "";
        }
        if (maskNode.isTextual()) {
            return normalizeImageSource(maskNode.asText());
        }
        if (maskNode.isObject()) {
            JsonNode imageUrl = maskNode.get("image_url");
            if (imageUrl != null && imageUrl.isTextual()) {
                return normalizeImageSource(imageUrl.asText());
            }
        }
        return "";
    }

    private ObjectNode readOauthImageResponse(HttpResponse<InputStream> upstream, String responseFormat) {
        try (InputStream input = upstream.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            ArrayNode data = objectMapper.createArrayNode();
            long created = Instant.now().getEpochSecond();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode event = objectMapper.readTree(payload);
                String type = text(event.get("type"));
                if ("response.output_item.done".equals(type)) {
                    JsonNode item = event.get("item");
                    if (item != null && "image_generation_call".equals(text(item.get("type")))) {
                        appendImageResult(data, item, responseFormat);
                    }
                    continue;
                }
                if ("response.completed".equals(type)) {
                    JsonNode responseNode = event.get("response");
                    if (responseNode != null && responseNode.path("created_at").canConvertToLong()) {
                        created = responseNode.path("created_at").asLong();
                    }
                    JsonNode output = responseNode == null ? null : responseNode.get("output");
                    if (output instanceof ArrayNode items) {
                        for (JsonNode item : items) {
                            if ("image_generation_call".equals(text(item.get("type")))) {
                                appendImageResult(data, item, responseFormat);
                            }
                        }
                    }
                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("created", created);
                    result.set("data", data);
                    if (data.isEmpty()) {
                        throw new OpenAiApiErrorException(502, "api_error", "No images returned from responses API");
                    }
                    return result;
                }
            }
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "api_error", "Failed to read OpenAI image response");
        }
        throw new OpenAiApiErrorException(502, "api_error", "Upstream stream ended without a completed image response");
    }

    private void appendImageResult(ArrayNode data, JsonNode item, String responseFormat) {
        String result = text(item.get("result"));
        if (result.isBlank()) {
            return;
        }
        String normalizedFormat = responseFormat == null || responseFormat.isBlank()
                ? "b64_json"
                : responseFormat.trim().toLowerCase(Locale.ROOT);
        String mimeType = switch (text(item.get("output_format")).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
        ObjectNode out = objectMapper.createObjectNode();
        if ("url".equals(normalizedFormat)) {
            out.put("url", "data:" + mimeType + ";base64," + result);
        } else {
            out.put("b64_json", result);
        }
        String revisedPrompt = text(item.get("revised_prompt"));
        if (!revisedPrompt.isBlank()) {
            out.put("revised_prompt", revisedPrompt);
        }
        data.add(out);
    }

    private byte[] rewriteModelIfJson(byte[] body, String contentType, AdminAccountResponse account) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
            return body;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof ObjectNode objectNode)) {
                return body;
            }
            String requestedModel = objectNode.path("model").asText("gpt-image-2").trim();
            if (requestedModel.isBlank()) {
                requestedModel = "gpt-image-2";
            }
            if (!requestedModel.toLowerCase(Locale.ROOT).startsWith("gpt-image-")) {
                throw new OpenAiApiErrorException(400, "invalid_request_error", "images endpoint requires an image model, got \"" + requestedModel + "\"");
            }
            String mapped = resolveMappedModel(account, requestedModel);
            objectNode.put("model", mapped);
            return objectMapper.writeValueAsBytes(objectNode);
        } catch (OpenAiApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private HttpResponse<byte[]> sendBytes(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenAiApiErrorException(502, "upstream_error", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "upstream_error", "Upstream request failed");
        }
    }

    private HttpResponse<InputStream> sendStream(AdminAccountResponse account, HttpRequest request) {
        try {
            return buildHttpClient(account).send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenAiApiErrorException(502, "upstream_error", "Upstream request interrupted");
        } catch (IOException ex) {
            throw new OpenAiApiErrorException(502, "upstream_error", "Upstream request failed");
        }
    }

    private void writeByteResponse(HttpServletResponse response, HttpResponse<byte[]> upstream) {
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            if (name == null || RESPONSE_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        if (response.getContentType() == null) {
            response.setContentType(upstream.headers().firstValue("content-type").orElse("application/json"));
        }
        try {
            response.getOutputStream().write(upstream.body());
            response.flushBuffer();
        } catch (IOException ex) {
            throw new HttpStatusException(500, "failed to write upstream response");
        }
    }

    private HttpClient buildHttpClient(AdminAccountResponse account) {
        AdminProxyResponse proxy = account.proxy_id() == null || account.proxy_id() <= 0
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

    private String buildImagesUrl(AdminAccountResponse account, String endpoint) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_OPENAI_BASE_URL;
        }
        baseUrl = upstreamUrlGuard.normalizeAccountBaseUrl(account.platform(), account.type(), baseUrl, DEFAULT_OPENAI_BASE_URL);
        String normalized = trimTrailingSlash(baseUrl);
        String relative = endpoint.startsWith("/v1") ? endpoint.substring("/v1".length()) : endpoint;
        if (normalized.endsWith(endpoint) || normalized.endsWith(relative)) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + relative;
        }
        return normalized + endpoint;
    }

    private String resolveEndpoint(HttpServletRequest request) {
        if (isEditRequest(request)) {
            return "/v1/images/edits";
        }
        return "/v1/images/generations";
    }

    private boolean isEditRequest(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        return path != null && path.contains("/images/edits");
    }

    private void copyAllowedHeaders(HttpServletRequest inbound, HttpRequest.Builder builder, Set<String> allowlist) {
        inbound.getHeaderNames().asIterator().forEachRemaining(name -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!allowlist.contains(lower)) {
                return;
            }
            inbound.getHeaders(name).asIterator().forEachRemaining(value -> builder.header(name, value));
        });
    }

    private void applyCustomUserAgent(HttpRequest.Builder builder, AdminAccountResponse account) {
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.setHeader("User-Agent", customUserAgent);
            return;
        }
        if (isOauthLikeType(account.type())) {
            builder.setHeader("User-Agent", "codex_cli_rs/0.125.0");
        }
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private OpenAiApiErrorException translateOpenAiError(HttpResponse<InputStream> upstream) {
        String message = readOpenAiErrorMessageFromStream(upstream);
        if (upstream.statusCode() >= 500) {
            return new OpenAiApiErrorException(502, "upstream_error", message);
        }
        if (upstream.statusCode() == 401 || upstream.statusCode() == 403) {
            return new OpenAiApiErrorException(upstream.statusCode(), "permission_error", message);
        }
        return new OpenAiApiErrorException(upstream.statusCode(), "invalid_request_error", message);
    }

    private void throwIfFailoverRequiredBytes(HttpResponse<byte[]> upstream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status != 429 && status != 529) {
            return;
        }
        throw new OpenAiUpstreamFailoverException(status, "rate_limit_error", readOpenAiErrorMessageFromBytes(upstream));
    }

    private void throwIfFailoverRequiredStream(HttpResponse<InputStream> upstream) {
        int status = upstream == null ? 0 : upstream.statusCode();
        if (status != 429 && status != 529) {
            return;
        }
        throw new OpenAiUpstreamFailoverException(status, "rate_limit_error", readOpenAiErrorMessageFromStream(upstream));
    }

    private String readOpenAiErrorMessageFromStream(HttpResponse<InputStream> upstream) {
        String message = "OpenAI upstream request failed";
        try (InputStream input = upstream.body()) {
            byte[] body = input == null ? new byte[0] : input.readAllBytes();
            JsonNode node = objectMapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isObject()) {
                String upstreamMessage = text(error.get("message"));
                if (!upstreamMessage.isEmpty()) {
                    message = upstreamMessage;
                }
            }
        } catch (Exception ignored) {
        }
        return message;
    }

    private String readOpenAiErrorMessageFromBytes(HttpResponse<byte[]> upstream) {
        String message = "OpenAI upstream request failed";
        try {
            byte[] body = upstream.body();
            JsonNode node = objectMapper.readTree(body == null ? new byte[0] : body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isObject()) {
                String upstreamMessage = text(error.get("message"));
                if (!upstreamMessage.isEmpty()) {
                    message = upstreamMessage;
                }
            }
        } catch (Exception ignored) {
        }
        return message;
    }

    private void copyTextIfPresent(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode value = source.get(fieldName);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(fieldName, value.asText().trim());
        }
    }

    private boolean hasImageInputs(ObjectNode objectNode) {
        if (objectNode == null) {
            return false;
        }
        JsonNode image = objectNode.get("image");
        if (image != null && !image.isNull()) {
            if (image.isTextual() && !image.asText().isBlank()) {
                return true;
            }
            if (image.isObject() && resolveMaskSource(image).isEmpty()) {
                JsonNode imageUrl = image.get("image_url");
                if (imageUrl != null && imageUrl.isTextual() && !imageUrl.asText().isBlank()) {
                    return true;
                }
            }
        }
        JsonNode images = objectNode.get("images");
        if (!(images instanceof ArrayNode items)) {
            return false;
        }
        for (JsonNode item : items) {
            if (item.isTextual() && !item.asText().isBlank()) {
                return true;
            }
            if (item.isObject()) {
                JsonNode imageUrl = item.get("image_url");
                if (imageUrl != null && imageUrl.isTextual() && !imageUrl.asText().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyMultipartFormField(ObjectNode parsed, ArrayNode images, String fieldName, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            return;
        }
        String normalizedField = fieldName.toLowerCase(Locale.ROOT);
        switch (normalizedField) {
            case "model", "prompt", "size", "response_format", "quality", "background",
                    "output_format", "moderation", "input_fidelity", "style" -> parsed.put(fieldName, value);
            case "stream" -> parsed.put("stream", parseBooleanField("stream", value));
            case "n" -> parsed.put("n", parsePositiveIntegerField("n", value));
            case "output_compression" -> parsed.put("output_compression", parseIntegerField("output_compression", value));
            case "partial_images" -> parsed.put("partial_images", parseIntegerField("partial_images", value));
            case "image" -> images.add(normalizeImageSource(value));
            case "mask" -> parsed.put("mask", normalizeImageSource(value));
            default -> {
                if (normalizedField.startsWith("image[")) {
                    images.add(normalizeImageSource(value));
                }
            }
        }
    }

    private void applyMultipartFileField(
            ObjectNode parsed,
            ArrayNode images,
            String fieldName,
            String fileName,
            String declaredContentType,
            byte[] partBytes
    ) {
        if (partBytes == null || partBytes.length == 0) {
            return;
        }
        String dataUrl = toImageDataUrl(partBytes, declaredContentType, fileName);
        String normalizedField = fieldName.toLowerCase(Locale.ROOT);
        if ("mask".equals(normalizedField)) {
            parsed.put("mask", dataUrl);
            return;
        }
        if ("image".equals(normalizedField) || normalizedField.startsWith("image[")) {
            images.add(dataUrl);
        }
    }

    private boolean parseBooleanField(String fieldName, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new OpenAiApiErrorException(400, "invalid_request_error", "invalid " + fieldName + " field value");
    }

    private int parsePositiveIntegerField(String fieldName, String value) {
        int parsed = parseIntegerField(fieldName, value);
        if (parsed <= 0) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", fieldName + " must be greater than 0");
        }
        return parsed;
    }

    private int parseIntegerField(String fieldName, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new OpenAiApiErrorException(400, "invalid_request_error", "invalid " + fieldName + " field value");
        }
    }

    private byte[] readPartBytes(FileItemStream item) throws IOException {
        try (InputStream input = item.openStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > OPENAI_IMAGE_MAX_UPLOAD_PART_SIZE) {
                    throw new OpenAiApiErrorException(400, "invalid_request_error", "multipart upload part is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String toImageDataUrl(byte[] bytes, String declaredContentType, String fileName) {
        String mimeType = declaredContentType == null ? "" : declaredContentType.trim();
        if (mimeType.isEmpty()) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                String guessed = URLConnection.guessContentTypeFromStream(input);
                if (guessed != null && !guessed.isBlank()) {
                    mimeType = guessed.trim();
                }
            } catch (IOException ignored) {
            }
        }
        if (mimeType.isEmpty() && fileName != null && !fileName.isBlank()) {
            String guessed = URLConnection.guessContentTypeFromName(fileName);
            if (guessed != null && !guessed.isBlank()) {
                mimeType = guessed.trim();
            }
        }
        if (mimeType.isEmpty()) {
            mimeType = "image/png";
        }
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String resolveMappedModel(AdminAccountResponse account, String requestedModel) {
        Map<String, String> mapping = extractStringMap(account.credentials() == null ? null : account.credentials().get("model_mapping"));
        if (mapping.isEmpty()) {
            return requestedModel;
        }
        String exact = mapping.get(requestedModel);
        if (exact != null && !exact.isBlank()) {
            return exact.trim();
        }
        String bestMatch = null;
        int bestScore = -1;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String pattern = entry.getKey();
            String target = entry.getValue();
            if (pattern == null || target == null || !pattern.contains("*")) {
                continue;
            }
            String regex = java.util.regex.Pattern.quote(pattern).replace("\\*", ".*");
            if (!requestedModel.matches(regex)) {
                continue;
            }
            int score = pattern.replace("*", "").length();
            if (score > bestScore) {
                bestScore = score;
                bestMatch = target.trim();
            }
        }
        return bestMatch == null || bestMatch.isBlank() ? requestedModel : bestMatch;
    }

    private Map<String, String> extractStringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString().trim();
            String mapped = entry.getValue().toString().trim();
            if (!key.isEmpty() && !mapped.isEmpty()) {
                result.put(key, mapped);
            }
        }
        return result;
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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

    private static final class ByteArrayUploadContext implements UploadContext {
        private final byte[] body;
        private final String contentType;

        private ByteArrayUploadContext(byte[] body, String contentType) {
            this.body = body == null ? new byte[0] : body;
            this.contentType = contentType == null ? "" : contentType;
        }

        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        public int getContentLength() {
            return body.length;
        }

        @Override
        public long contentLength() {
            return body.length;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }
    }
}
