package org.apiprivaterouter.javabackend.admin.ops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayApiKeyRuntimeView;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayGroupSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayUserSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.repository.GatewayRuntimeRepository;
import org.apiprivaterouter.javabackend.gateway.service.GatewayAnthropicMessagesService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayAnthropicResponsesService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayGeminiMessagesCompatService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayGeminiProxyService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiChatCompletionsService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiImagesService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiMessagesDispatchService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesService;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AdminOpsRetryExecutor {

    private static final int MAX_FAILOVER_ATTEMPTS = 3;
    private static final int RESPONSE_CAPTURE_LIMIT = 64 * 1024;
    private static final int RESPONSE_PREVIEW_LIMIT = 8 * 1024;
    private static final Set<String> REQUEST_HEADER_ALLOWLIST = Set.of(
            "anthropic-beta",
            "anthropic-version"
    );

    private final AdminAccountRepository accountRepository;
    private final GatewayRuntimeRepository gatewayRuntimeRepository;
    private final GatewayAnthropicMessagesService anthropicMessagesService;
    private final GatewayAnthropicResponsesService anthropicResponsesService;
    private final GatewayGeminiMessagesCompatService geminiMessagesCompatService;
    private final GatewayGeminiProxyService geminiProxyService;
    private final GatewayOpenAiMessagesDispatchService openAiMessagesDispatchService;
    private final GatewayOpenAiResponsesService openAiResponsesService;
    private final GatewayOpenAiChatCompletionsService openAiChatCompletionsService;
    private final GatewayOpenAiImagesService openAiImagesService;
    private final ObjectMapper objectMapper;

    public AdminOpsRetryExecutor(
            AdminAccountRepository accountRepository,
            GatewayRuntimeRepository gatewayRuntimeRepository,
            GatewayAnthropicMessagesService anthropicMessagesService,
            GatewayAnthropicResponsesService anthropicResponsesService,
            GatewayGeminiMessagesCompatService geminiMessagesCompatService,
            GatewayGeminiProxyService geminiProxyService,
            GatewayOpenAiMessagesDispatchService openAiMessagesDispatchService,
            GatewayOpenAiResponsesService openAiResponsesService,
            GatewayOpenAiChatCompletionsService openAiChatCompletionsService,
            GatewayOpenAiImagesService openAiImagesService,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.gatewayRuntimeRepository = gatewayRuntimeRepository;
        this.anthropicMessagesService = anthropicMessagesService;
        this.anthropicResponsesService = anthropicResponsesService;
        this.geminiMessagesCompatService = geminiMessagesCompatService;
        this.geminiProxyService = geminiProxyService;
        this.openAiMessagesDispatchService = openAiMessagesDispatchService;
        this.openAiResponsesService = openAiResponsesService;
        this.openAiChatCompletionsService = openAiChatCompletionsService;
        this.openAiImagesService = openAiImagesService;
        this.objectMapper = objectMapper;
    }

    public RetryExecutionResult executeClientRetry(Map<String, Object> errorLog) {
        RetryRequestType requestType = detectRequestType(errorLog);
        Long groupId = longValue(errorLog.get("group_id"));
        if (groupId == null || groupId <= 0) {
            return failed("group_id missing; cannot reselect account");
        }
        byte[] body = effectiveRequestBody(requestType, stringValue(errorLog.get("request_body")));
        List<Long> excludedAccountIds = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_FAILOVER_ATTEMPTS; attempt++) {
            GatewayAccountSummary summary = gatewayRuntimeRepository
                    .findPreferredAccount(groupId, platformHintForClientSelection(requestType, errorLog), requireCompact(errorLog), excludedAccountIds)
                    .orElse(null);
            if (summary == null) {
                return failed("No available accounts");
            }
            RetryExecutionResult result = executeWithAccount(errorLog, summary.id(), requestType, body);
            if (result.success()) {
                return result;
            }
            if (!result.failover()) {
                return result;
            }
            excludedAccountIds.add(summary.id());
        }
        return failed("retry failed after exhausting account failovers");
    }

    public RetryExecutionResult executePinnedRetry(Map<String, Object> errorLog, long pinnedAccountId) {
        RetryRequestType requestType = detectRequestType(errorLog);
        byte[] body = effectiveRequestBody(requestType, stringValue(errorLog.get("request_body")));
        return executeWithAccount(errorLog, pinnedAccountId, requestType, body);
    }

    private RetryExecutionResult executeWithAccount(
            Map<String, Object> errorLog,
            long accountId,
            RetryRequestType requestType,
            byte[] body
    ) {
        AdminAccountResponse account = accountRepository.getAccount(accountId).orElse(null);
        if (account == null) {
            return failed("account not found");
        }
        if (!account.schedulable() || !"active".equalsIgnoreCase(account.status())) {
            return failed("account is not schedulable");
        }
        Long originalGroupId = longValue(errorLog.get("group_id"));
        if (originalGroupId != null && originalGroupId > 0 && (account.group_ids() == null || !account.group_ids().contains(originalGroupId))) {
            return failed("pinned account is not in the same group as the original request");
        }

        GatewayRuntimeContext runtimeContext = buildRuntimeContext(account, originalGroupId);
        ReplayHttpServletRequest request = new ReplayHttpServletRequest(errorLog, body);
        ReplayHttpServletResponse response = new ReplayHttpServletResponse();

        try {
            dispatch(runtimeContext, request, response, body, requestType, errorLog);
            return successful(account.id(), response.getStatus(), response.upstreamRequestId(), response.preview(), response.truncated());
        } catch (OpenAiUpstreamFailoverException ex) {
            return failedWithStatus(account.id(), ex.getUpstreamStatus(), response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), true);
        } catch (GatewayGeminiProxyService.RecoverableUpstreamException ex) {
            return failedWithStatus(account.id(), ex.getStatusCode(), response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), true);
        } catch (OpenAiApiErrorException ex) {
            return failedWithStatus(account.id(), ex.getStatus(), response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), false);
        } catch (AnthropicApiErrorException ex) {
            return failedWithStatus(account.id(), ex.getStatus(), response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), false);
        } catch (ApiErrorException ex) {
            return failedWithStatus(account.id(), ex.getStatus(), response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), false);
        } catch (HttpStatusException ex) {
            return failedWithStatus(account.id(), ex.getStatus(), response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), false);
        } catch (RuntimeException ex) {
            int status = response.getStatus() > 0 ? response.getStatus() : HttpStatus.BAD_GATEWAY.value();
            return failedWithStatus(account.id(), status, response.upstreamRequestId(), response.preview(), response.truncated(), ex.getMessage(), false);
        }
    }

    private void dispatch(
            GatewayRuntimeContext runtimeContext,
            ReplayHttpServletRequest request,
            ReplayHttpServletResponse response,
            byte[] body,
            RetryRequestType requestType,
            Map<String, Object> errorLog
    ) {
        switch (requestType) {
            case OPENAI_RESPONSES -> {
                if ("openai".equalsIgnoreCase(stringValue(errorLog.get("platform")))) {
                    openAiResponsesService.forward(runtimeContext, request, response, body);
                } else {
                    anthropicResponsesService.forward(runtimeContext, request, response, body);
                }
            }
            case OPENAI_CHAT_COMPLETIONS -> openAiChatCompletionsService.forward(runtimeContext, request, response, body);
            case OPENAI_IMAGES -> openAiImagesService.forward(runtimeContext, request, response, body);
            case GEMINI_V1BETA -> {
                String model = stringValue(errorLog.get("model"));
                String action = Boolean.TRUE.equals(errorLog.get("stream")) ? "streamGenerateContent" : "generateContent";
                geminiProxyService.forward(runtimeContext, request, response, body, model, action);
            }
            case GEMINI_MESSAGES_COMPAT -> geminiMessagesCompatService.forward(runtimeContext, request, response, body);
            case OPENAI_MESSAGES_DISPATCH -> openAiMessagesDispatchService.forward(runtimeContext, request, response, body);
            case ANTHROPIC_MESSAGES -> anthropicMessagesService.forward(runtimeContext, request, response, body);
        }
    }

    private GatewayRuntimeContext buildRuntimeContext(AdminAccountResponse account, Long groupId) {
        GatewayGroupSummary group = groupId == null ? null : gatewayRuntimeRepository.findGroup(groupId).orElse(null);
        GatewayApiKeyRuntimeView apiKey = new GatewayApiKeyRuntimeView(
                0L,
                0L,
                "",
                "ops-retry",
                "active",
                group == null ? null : group.id(),
                0.0d,
                0.0d,
                null,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                null,
                null,
                null,
                null,
                null,
                null,
                group
        );
        GatewayUserSummary user = new GatewayUserSummary(0L, "", "admin", "active", 0.0d);
        GatewayAccountSummary runtimeAccount = new GatewayAccountSummary(
                account.id(),
                account.name(),
                account.platform(),
                account.type(),
                account.status(),
                account.priority(),
                account.proxy_id(),
                account.credentials(),
                account.extra()
        );
        return new GatewayRuntimeContext(apiKey, user, null, runtimeAccount);
    }

    private RetryRequestType detectRequestType(Map<String, Object> errorLog) {
        String path = stringValue(errorLog.get("request_path")).toLowerCase(Locale.ROOT);
        String platform = stringValue(errorLog.get("platform")).toLowerCase(Locale.ROOT);
        if (path.contains("/chat/completions")) {
            return RetryRequestType.OPENAI_CHAT_COMPLETIONS;
        }
        if (path.contains("/images/")) {
            return RetryRequestType.OPENAI_IMAGES;
        }
        if (path.contains("/v1beta/")) {
            return path.contains("/messages") ? RetryRequestType.GEMINI_MESSAGES_COMPAT : RetryRequestType.GEMINI_V1BETA;
        }
        if (path.contains("/responses")) {
            return RetryRequestType.OPENAI_RESPONSES;
        }
        if (path.contains("/v1/messages")) {
            if ("openai".equals(platform)) {
                return RetryRequestType.OPENAI_MESSAGES_DISPATCH;
            }
            if ("gemini".equals(platform)) {
                return RetryRequestType.GEMINI_MESSAGES_COMPAT;
            }
            return RetryRequestType.ANTHROPIC_MESSAGES;
        }
        return "openai".equals(platform) ? RetryRequestType.OPENAI_MESSAGES_DISPATCH : RetryRequestType.ANTHROPIC_MESSAGES;
    }

    private String platformHintForClientSelection(RetryRequestType requestType, Map<String, Object> errorLog) {
        return switch (requestType) {
            case OPENAI_RESPONSES, OPENAI_CHAT_COMPLETIONS, OPENAI_IMAGES, OPENAI_MESSAGES_DISPATCH -> "openai";
            case GEMINI_V1BETA, GEMINI_MESSAGES_COMPAT -> "gemini";
            case ANTHROPIC_MESSAGES -> {
                String platform = stringValue(errorLog.get("platform"));
                yield platform.isBlank() ? "anthropic" : platform;
            }
        };
    }

    private boolean requireCompact(Map<String, Object> errorLog) {
        String path = stringValue(errorLog.get("request_path")).toLowerCase(Locale.ROOT);
        return path.contains("/responses/compact");
    }

    private byte[] effectiveRequestBody(RetryRequestType requestType, String body) {
        String normalized = body == null ? "" : body.trim();
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private RetryExecutionResult successful(Long usedAccountId, int httpStatusCode, String upstreamRequestId, String responsePreview, boolean truncated) {
        return new RetryExecutionResult(true, false, usedAccountId, httpStatusCode, upstreamRequestId, responsePreview, truncated, "");
    }

    private RetryExecutionResult failed(String message) {
        return new RetryExecutionResult(false, false, null, 0, "", "", false, message == null ? "" : message);
    }

    private RetryExecutionResult failedWithStatus(
            Long usedAccountId,
            int httpStatusCode,
            String upstreamRequestId,
            String responsePreview,
            boolean truncated,
            String message,
            boolean failover
    ) {
        return new RetryExecutionResult(false, failover, usedAccountId, httpStatusCode, upstreamRequestId, responsePreview, truncated, message == null ? "" : message);
    }

    public record RetryExecutionResult(
            boolean success,
            boolean failover,
            Long usedAccountId,
            int httpStatusCode,
            String upstreamRequestId,
            String responsePreview,
            boolean responseTruncated,
            String errorMessage
    ) {
    }

    private enum RetryRequestType {
        ANTHROPIC_MESSAGES,
        OPENAI_RESPONSES,
        OPENAI_CHAT_COMPLETIONS,
        OPENAI_IMAGES,
        OPENAI_MESSAGES_DISPATCH,
        GEMINI_V1BETA,
        GEMINI_MESSAGES_COMPAT
    }

    private static final class ReplayHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final String requestUri;
        private final String contentType;
        private final Map<String, List<String>> headers;

        private ReplayHttpServletRequest(Map<String, Object> errorLog, byte[] body) {
            super(emptyRequestDelegate());
            this.body = body == null ? new byte[0] : body;
            this.requestUri = normalizePath(errorLog == null ? null : errorLog.get("request_path"));
            this.contentType = resolveContentType(errorLog);
            this.headers = buildHeaders(errorLog, this.contentType);
        }

        @Override
        public String getRequestURI() {
            return requestUri;
        }

        @Override
        public String getMethod() {
            return "POST";
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String getHeader(String name) {
            List<String> values = headers.get(normalizeHeaderName(name));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            List<String> values = headers.get(normalizeHeaderName(name));
            return Collections.enumeration(values == null ? List.of() : values);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        private static String normalizePath(Object rawPath) {
            String path = rawPath == null ? "" : String.valueOf(rawPath).trim();
            if (path.isBlank()) {
                return "/";
            }
            return path.startsWith("/") ? path : "/" + path;
        }

        private static String resolveContentType(Map<String, Object> errorLog) {
            Map<String, String> stored = parseHeaderMap(errorLog == null ? null : errorLog.get("request_headers"));
            String explicit = stored.get("content-type");
            return explicit == null || explicit.isBlank() ? "application/json" : explicit;
        }

        private static Map<String, List<String>> buildHeaders(Map<String, Object> errorLog, String contentType) {
            Map<String, List<String>> merged = new LinkedHashMap<>();
            merged.put("content-type", List.of(contentType));
            Map<String, String> stored = parseHeaderMap(errorLog == null ? null : errorLog.get("request_headers"));
            for (Map.Entry<String, String> entry : stored.entrySet()) {
                String key = normalizeHeaderName(entry.getKey());
                if (!REQUEST_HEADER_ALLOWLIST.contains(key) && !"user-agent".equals(key) && !"originator".equals(key) && !"openai-beta".equals(key)) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!value.isEmpty()) {
                    merged.put(key, List.of(value));
                }
            }
            String userAgent = errorLog == null ? "" : String.valueOf(errorLog.getOrDefault("user_agent", "")).trim();
            if (!userAgent.isEmpty()) {
                merged.put("user-agent", List.of(userAgent));
            }
            return merged;
        }

        private static Map<String, String> parseHeaderMap(Object rawHeaders) {
            if (!(rawHeaders instanceof String raw) || raw.isBlank()) {
                return Map.of();
            }
            try {
                return new ObjectMapper().readValue(raw, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception ignored) {
                return Map.of();
            }
        }

        private static String normalizeHeaderName(String name) {
            return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        }

        private static HttpServletRequest emptyRequestDelegate() {
            return (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class[]{HttpServletRequest.class},
                    (proxy, method, args) -> defaultValue(method.getReturnType())
            );
        }
    }

    private static final class ReplayHttpServletResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private int status = HttpServletResponse.SC_OK;
        private String contentType;
        private String characterEncoding = StandardCharsets.UTF_8.name();
        private final ServletOutputStream outputStream = new ServletOutputStream() {
            @Override
            public void write(int b) {
                if (body.size() < RESPONSE_CAPTURE_LIMIT) {
                    body.write(b);
                }
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }
        };

        private ReplayHttpServletResponse() {
            super(emptyResponseDelegate());
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            return new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);
        }

        @Override
        public void flushBuffer() {
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            this.status = sc;
        }

        @Override
        public void setHeader(String name, String value) {
            headers.put(normalizeHeaderName(name), new ArrayList<>(List.of(value)));
        }

        @Override
        public void addHeader(String name, String value) {
            headers.computeIfAbsent(normalizeHeaderName(name), ignored -> new ArrayList<>()).add(value);
        }

        @Override
        public String getHeader(String name) {
            List<String> values = headers.get(normalizeHeaderName(name));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public Collection<String> getHeaders(String name) {
            List<String> values = headers.get(normalizeHeaderName(name));
            return values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.keySet();
        }

        @Override
        public void setContentType(String type) {
            this.contentType = type;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setCharacterEncoding(String charset) {
            this.characterEncoding = charset;
        }

        @Override
        public String getCharacterEncoding() {
            return characterEncoding;
        }

        private String upstreamRequestId() {
            for (String key : List.of("x-request-id", "x-request-id".toLowerCase(Locale.ROOT), "x-request-id".toUpperCase(Locale.ROOT))) {
                String value = getHeader(key);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return "";
        }

        private String preview() {
            byte[] bytes = body.toByteArray();
            if (bytes.length == 0) {
                return "";
            }
            int length = Math.min(bytes.length, RESPONSE_PREVIEW_LIMIT);
            return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
        }

        private boolean truncated() {
            return body.size() >= RESPONSE_PREVIEW_LIMIT;
        }

        private String normalizeHeaderName(String name) {
            return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        }

        private static HttpServletResponse emptyResponseDelegate() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class[]{HttpServletResponse.class},
                    (proxy, method, args) -> defaultValue(method.getReturnType())
            );
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == null || !returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (byte.class.equals(returnType)) {
            return (byte) 0;
        }
        if (short.class.equals(returnType)) {
            return (short) 0;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (float.class.equals(returnType)) {
            return 0.0f;
        }
        if (double.class.equals(returnType)) {
            return 0.0d;
        }
        if (char.class.equals(returnType)) {
            return '\0';
        }
        return null;
    }
}
