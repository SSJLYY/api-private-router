package org.apiprivaterouter.javabackend.admin.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.jsonwebtoken.Jwts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AccountTestEvent;
import org.apiprivaterouter.javabackend.admin.account.model.AccountTestRequest;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.antigravity.service.AntigravityOAuthService;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.gemini.service.GeminiOAuthGatewayService;
import org.apiprivaterouter.javabackend.admin.openai.service.OpenAiOAuthService;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class AdminAccountTestService {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages?beta=true";
    private static final String CHATGPT_CODEX_API_URL = "https://chatgpt.com/backend-api/codex/responses";
    private static final String OPENAI_DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String GEMINI_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String GEMINI_CODE_ASSIST_BASE_URL = "https://cloudcode-pa.googleapis.com";
    private static final String GEMINI_VERTEX_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GEMINI_VERTEX_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String GEMINI_VERTEX_DEFAULT_LOCATION = "us-central1";
    private static final String VERTEX_ANTHROPIC_VERSION = "vertex-2023-10-16";
    private static final String CLAUDE_DEFAULT_TEST_MODEL = "claude-sonnet-4-5-20250929";
    private static final String OPENAI_DEFAULT_TEST_MODEL = "gpt-5.4";
    private static final String GEMINI_DEFAULT_TEST_MODEL = "gemini-2.5-pro";
    private static final String DEFAULT_GEMINI_TEXT_PROMPT = "hi";
    private static final String DEFAULT_GEMINI_IMAGE_PROMPT = "Generate a cute orange cat astronaut sticker on a clean pastel background.";
    private static final String DEFAULT_OPENAI_IMAGE_PROMPT = "Generate a cute orange cat astronaut sticker on a clean pastel background.";
    private static final String DEFAULT_OPENAI_INSTRUCTIONS = "You are a helpful coding assistant.";
    private static final String CLAUDE_DEFAULT_BETA_HEADER = "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14";
    private static final String CLAUDE_API_KEY_BETA_HEADER = "claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14";
    private static final Map<String, String> CLAUDE_DEFAULT_HEADERS = Map.ofEntries(
            Map.entry("User-Agent", "claude-cli/2.1.92 (external, cli)"),
            Map.entry("X-Stainless-Lang", "js"),
            Map.entry("X-Stainless-Package-Version", "0.70.0"),
            Map.entry("X-Stainless-OS", "Linux"),
            Map.entry("X-Stainless-Arch", "arm64"),
            Map.entry("X-Stainless-Runtime", "node"),
            Map.entry("X-Stainless-Runtime-Version", "v24.13.0"),
            Map.entry("X-Stainless-Retry-Count", "0"),
            Map.entry("X-Stainless-Timeout", "600"),
            Map.entry("X-App", "cli"),
            Map.entry("Anthropic-Dangerous-Direct-Browser-Access", "true")
    );
    private static final String GEMINI_CLI_USER_AGENT = "GeminiCLI/0.1.5 (Windows; AMD64)";
    private static final String OPENAI_CODEX_USER_AGENT = "codex_cli_rs/0.125.0";
    private static final String OPENAI_CODEX_VERSION = "0.125.0";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final String TEST_MODE_COMPACT = "compact";
    private static final String TEST_MODE_DEFAULT = "default";
    private static final String CLAUDE_CODE_SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude.";
    private static final Pattern VERTEX_LOCATION_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern VERTEX_ANTHROPIC_DATED_MODEL_ID_PATTERN = Pattern.compile("^(.+)-([0-9]{8})$");
    private static final Pattern VERTEX_ANTHROPIC_ALREADY_DATED_ID_PATTERN = Pattern.compile("^.+@[0-9]{8}$");

    private final AdminAccountService accountService;
    private final AdminAccountRepository accountRepository;
    private final AdminProxyRepository proxyRepository;
    private final ClaudeOAuthService claudeOAuthService;
    private final OpenAiOAuthService openAiOAuthService;
    private final GeminiOAuthGatewayService geminiOAuthGatewayService;
    private final AntigravityOAuthService antigravityOAuthService;
    private final ObjectMapper objectMapper;

    public AdminAccountTestService(
            AdminAccountService accountService,
            AdminAccountRepository accountRepository,
            AdminProxyRepository proxyRepository,
            ClaudeOAuthService claudeOAuthService,
            OpenAiOAuthService openAiOAuthService,
            GeminiOAuthGatewayService geminiOAuthGatewayService,
            AntigravityOAuthService antigravityOAuthService,
            ObjectMapper objectMapper
    ) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.proxyRepository = proxyRepository;
        this.claudeOAuthService = claudeOAuthService;
        this.openAiOAuthService = openAiOAuthService;
        this.geminiOAuthGatewayService = geminiOAuthGatewayService;
        this.antigravityOAuthService = antigravityOAuthService;
        this.objectMapper = objectMapper;
    }

    public StreamingResponseBody buildAccountTestStream(long accountId, AccountTestRequest request) {
        AccountTestRequest effectiveRequest = request == null
                ? new AccountTestRequest(null, null, null)
                : request;

        return outputStream -> {
            SseWriter writer = new SseWriter(outputStream, objectMapper);
            boolean success = false;
            try {
                AdminAccountResponse account = accountService.getAccount(accountId);
                success = testAccount(account, effectiveRequest, writer);
                if (success) {
                    recoverAfterSuccessfulTest(account.id());
                }
            } catch (HttpStatusException ex) {
                writer.send(AccountTestEvent.error(ex.getMessage()));
            } catch (Exception ex) {
                writer.send(AccountTestEvent.error(cleanErrorMessage(ex)));
            } finally {
                if (success) {
                    writer.send(AccountTestEvent.complete(true, null));
                }
                writer.flush();
            }
        };
    }

    public BackgroundTestResult runTestBackground(long accountId, String modelId) {
        AccountTestRequest request = new AccountTestRequest(modelId, null, null);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CollectingSseWriter writer = new CollectingSseWriter(outputStream, objectMapper);
        Instant startedAt = Instant.now();
        AtomicBoolean success = new AtomicBoolean(false);
        try {
            AdminAccountResponse account = accountService.getAccount(accountId);
            success.set(testAccount(account, request, writer));
        } catch (Exception ex) {
            writer.capture(AccountTestEvent.error(cleanErrorMessage(ex)));
        } finally {
            if (success.get()) {
                writer.capture(AccountTestEvent.complete(true, null));
            }
            try {
                writer.flush();
            } catch (IOException ignored) {
                // In background mode the captured events are the source of truth.
            }
        }

        Instant finishedAt = Instant.now();
        String responseText = writer.joinContent().trim();
        String errorMessage = writer.errorMessage();
        String status = success.get() ? "success" : "failed";
        return new BackgroundTestResult(
                status,
                responseText,
                defaultIfBlank(errorMessage, ""),
                Duration.between(startedAt, finishedAt).toMillis(),
                startedAt,
                finishedAt
        );
    }

    private boolean testAccount(AdminAccountResponse account, AccountTestRequest request, SseWriter writer) throws Exception {
        if (account == null) {
            writer.send(AccountTestEvent.error("Account not found"));
            return false;
        }
        if (isOpenAi(account)) {
            return testOpenAi(account, request, writer);
        }
        if (isGemini(account)) {
            return testGemini(account, request, writer);
        }
        if (isAntigravity(account)) {
            return testAntigravity(account, request, writer);
        }
        return testClaude(account, request, writer);
    }

    private boolean testClaude(AdminAccountResponse account, AccountTestRequest request, SseWriter writer) throws Exception {
        String type = nullSafe(account.type());
        String model = normalizeClaudeModel(resolveMappedModel(account, defaultIfBlank(request.model_id(), CLAUDE_DEFAULT_TEST_MODEL)));
        if ("service_account".equals(type)) {
            model = normalizeVertexAnthropicModel(model);
        }
        Map<String, Object> payload = createClaudePayload(model);
        HttpRequest upstreamRequest;

        if (isClaudeOAuthLike(account)) {
            String accessToken = stringValue(account.credentials(), "access_token");
            if (accessToken == null) {
                writer.send(AccountTestEvent.error("No access token available"));
                return false;
            }
            HttpRequest.Builder builder = newRequestBuilder(account, CLAUDE_API_URL)
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .header("anthropic-beta", CLAUDE_DEFAULT_BETA_HEADER)
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));
            applyHeaders(builder, CLAUDE_DEFAULT_HEADERS);
            upstreamRequest = builder.build();
        } else if (isApiKey(account)) {
            String apiKey = stringValue(account.credentials(), "api_key");
            if (apiKey == null) {
                writer.send(AccountTestEvent.error("No API key available"));
                return false;
            }
            String apiUrl = trimTrailingSlash(resolveClaudeBaseUrl(account)) + "/v1/messages?beta=true";
            HttpRequest.Builder builder = newRequestBuilder(account, apiUrl)
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .header("anthropic-beta", CLAUDE_API_KEY_BETA_HEADER)
                    .header("x-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));
            applyHeaders(builder, CLAUDE_DEFAULT_HEADERS);
            upstreamRequest = builder.build();
        } else if ("service_account".equals(type)) {
            String accessToken = exchangeVertexServiceAccountAccessToken(account);
            byte[] vertexPayload = buildVertexAnthropicRequestBody(writeJson(payload).getBytes(StandardCharsets.UTF_8));
            String apiUrl = buildVertexAnthropicUrl(
                    resolveVertexProjectId(account),
                    resolveVertexLocation(account, model),
                    model,
                    true
            );
            HttpRequest.Builder builder = newRequestBuilder(account, apiUrl)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(vertexPayload));
            upstreamRequest = builder.build();
        } else {
            writer.send(AccountTestEvent.error("Unsupported account type: " + nullSafe(account.type())));
            return false;
        }

        writer.send(AccountTestEvent.testStart(model));
        HttpResponse<InputStream> response = send(account, upstreamRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writer.send(AccountTestEvent.error(buildHttpError(response)));
            return false;
        }
        return processClaudeStream(response.body(), writer);
    }

    private boolean testOpenAi(AdminAccountResponse account, AccountTestRequest request, SseWriter writer) throws Exception {
        String mode = normalizeTestMode(request.mode());
        String model = resolveMappedModel(account, defaultIfBlank(request.model_id(), OPENAI_DEFAULT_TEST_MODEL));
        if (TEST_MODE_COMPACT.equals(mode)) {
            model = resolveCompactMappedModel(account, model);
            return testOpenAiCompact(account, model, writer);
        }

        if (isOpenAiImageModel(model)) {
            return testOpenAiImage(account, model, request.prompt(), writer);
        }

        boolean oauth = isOpenAiOAuth(account);
        String apiUrl;
        String authToken;
        HttpRequest.Builder builder;

        if (oauth) {
            authToken = stringValue(account.credentials(), "access_token");
            if (authToken == null) {
                writer.send(AccountTestEvent.error("No access token available"));
                return false;
            }
            apiUrl = CHATGPT_CODEX_API_URL;
        } else if (isApiKey(account)) {
            authToken = stringValue(account.credentials(), "api_key");
            if (authToken == null) {
                writer.send(AccountTestEvent.error("No API key available"));
                return false;
            }
            apiUrl = buildOpenAiResponsesUrl(resolveOpenAiBaseUrl(account));
        } else {
            writer.send(AccountTestEvent.error("Unsupported account type: " + nullSafe(account.type())));
            return false;
        }

        Map<String, Object> payload = createOpenAiPayload(model, oauth);
        builder = newRequestBuilder(account, apiUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));

        if (oauth) {
            builder.header("accept", "text/event-stream");
            builder.header("Host", "chatgpt.com");
            String chatgptAccountId = stringValue(account.credentials(), "chatgpt_account_id");
            if (chatgptAccountId != null) {
                builder.header("chatgpt-account-id", chatgptAccountId);
            }
        }

        writer.send(AccountTestEvent.testStart(model));
        HttpResponse<InputStream> response = send(account, builder.build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writer.send(AccountTestEvent.error(buildHttpError(response)));
            return false;
        }
        return processOpenAiStream(response.body(), writer);
    }

    private boolean testOpenAiCompact(AdminAccountResponse account, String model, SseWriter writer) throws Exception {
        boolean oauth = isOpenAiOAuth(account);
        String authToken;
        String apiUrl;
        HttpRequest.Builder builder;

        if (oauth) {
            authToken = stringValue(account.credentials(), "access_token");
            if (authToken == null) {
                writer.send(AccountTestEvent.error("No access token available"));
                return false;
            }
            apiUrl = CHATGPT_CODEX_API_URL + "/compact";
        } else if (isApiKey(account)) {
            authToken = stringValue(account.credentials(), "api_key");
            if (authToken == null) {
                writer.send(AccountTestEvent.error("No API key available"));
                return false;
            }
            apiUrl = appendOpenAiResponsesRequestPathSuffix(buildOpenAiResponsesUrl(resolveOpenAiBaseUrl(account)), "/compact");
        } else {
            writer.send(AccountTestEvent.error("Unsupported account type: " + nullSafe(account.type())));
            return false;
        }

        builder = newRequestBuilder(account, apiUrl)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .header("OpenAI-Beta", "responses=experimental")
                .header("Originator", "codex_cli_rs")
                .header("User-Agent", OPENAI_CODEX_USER_AGENT)
                .header("Version", OPENAI_CODEX_VERSION)
                .header("Session_ID", compactProbeSessionId(account.id()))
                .header("Conversation_ID", compactProbeSessionId(account.id()))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(createOpenAiCompactPayload(model)), StandardCharsets.UTF_8));

        if (oauth) {
            builder.header("Host", "chatgpt.com");
            String chatgptAccountId = stringValue(account.credentials(), "chatgpt_account_id");
            if (chatgptAccountId != null) {
                builder.header("chatgpt-account-id", chatgptAccountId);
            }
        }

        writer.send(AccountTestEvent.testStart(model));
        HttpResponse<InputStream> response = send(account, builder.build());
        byte[] body = response.body().readAllBytes();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writer.send(AccountTestEvent.error("API returned " + response.statusCode() + ": " + new String(body, StandardCharsets.UTF_8)));
            return false;
        }
        writer.send(AccountTestEvent.content("Compact probe succeeded"));
        return true;
    }

    private boolean testOpenAiImage(AdminAccountResponse account, String model, String prompt, SseWriter writer) throws Exception {
        String effectivePrompt = defaultIfBlank(prompt, DEFAULT_OPENAI_IMAGE_PROMPT);
        if (isOpenAiOAuth(account)) {
            return testOpenAiImageOAuth(account, model, effectivePrompt, writer);
        }
        return testOpenAiImageApiKey(account, model, effectivePrompt, writer);
    }

    private boolean testOpenAiImageApiKey(AdminAccountResponse account, String model, String prompt, SseWriter writer) throws Exception {
        String apiKey = stringValue(account.credentials(), "api_key");
        if (apiKey == null) {
            writer.send(AccountTestEvent.error("No API key available"));
            return false;
        }
        String apiUrl = trimTrailingSlash(resolveOpenAiBaseUrl(account)) + "/v1/images/generations";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("response_format", "b64_json");

        writer.send(AccountTestEvent.testStart(model));
        HttpRequest request = newRequestBuilder(account, apiUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = send(account, request);
        byte[] body = response.body().readAllBytes();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writer.send(AccountTestEvent.error("API returned " + response.statusCode() + ": " + new String(body, StandardCharsets.UTF_8)));
            return false;
        }
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            writer.send(AccountTestEvent.error("No images returned from API"));
            return false;
        }
        for (JsonNode item : data) {
            if (item.hasNonNull("revised_prompt")) {
                writer.send(AccountTestEvent.content(item.path("revised_prompt").asText("")));
            }
            String image = trimToNull(item.path("b64_json").asText(null));
            if (image != null) {
                writer.send(AccountTestEvent.image("data:image/png;base64," + image, "image/png"));
            }
        }
        return true;
    }

    private boolean testOpenAiImageOAuth(AdminAccountResponse account, String model, String prompt, SseWriter writer) throws Exception {
        String accessToken = stringValue(account.credentials(), "access_token");
        if (accessToken == null) {
            writer.send(AccountTestEvent.error("No access token available"));
            return false;
        }

        Map<String, Object> payload = createOpenAiOAuthImagePayload(model, prompt);
        HttpRequest.Builder builder = newRequestBuilder(account, CHATGPT_CODEX_API_URL)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + accessToken)
                .header("OpenAI-Beta", "responses=experimental")
                .header("Originator", "opencode")
                .header("User-Agent", defaultIfBlank(stringValue(account.credentials(), "user_agent"), OPENAI_CODEX_USER_AGENT))
                .header("Host", "chatgpt.com")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));
        String chatgptAccountId = stringValue(account.credentials(), "chatgpt_account_id");
        if (chatgptAccountId != null) {
            builder.header("chatgpt-account-id", chatgptAccountId);
        }

        writer.send(AccountTestEvent.testStart(model));
        writer.send(AccountTestEvent.content("Calling Codex /responses image tool...\n"));
        HttpResponse<InputStream> response = send(account, builder.build());
        byte[] body = response.body().readAllBytes();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writer.send(AccountTestEvent.error("Responses API returned " + response.statusCode() + ": " + extractErrorMessage(body)));
            return false;
        }
        return processOpenAiImageResponse(body, writer);
    }

    private boolean testGemini(AdminAccountResponse account, AccountTestRequest request, SseWriter writer) throws Exception {
        String requestedModel = defaultIfBlank(request.model_id(), GEMINI_DEFAULT_TEST_MODEL);
        String model = resolveMappedModel(account, requestedModel);
        byte[] payload = createGeminiPayload(model, request.prompt());
        HttpRequest requestToSend;
        String type = nullSafe(account.type());

        if ("apikey".equals(type)) {
            requestToSend = buildGeminiApiKeyRequest(account, model, payload);
        } else if ("oauth".equals(type)) {
            requestToSend = buildGeminiOAuthRequest(account, model, payload);
        } else if ("service_account".equals(type)) {
            requestToSend = buildGeminiServiceAccountRequest(account, model, payload);
        } else {
            writer.send(AccountTestEvent.error("Unsupported account type: " + type));
            return false;
        }

        writer.send(AccountTestEvent.testStart(model));
        HttpResponse<InputStream> response = send(account, requestToSend);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writer.send(AccountTestEvent.error(buildHttpError(response)));
            return false;
        }
        return processGeminiStream(response.body(), writer);
    }

    private boolean testAntigravity(AdminAccountResponse account, AccountTestRequest request, SseWriter writer) throws Exception {
        String model = resolveMappedModel(account, defaultIfBlank(request.model_id(), CLAUDE_DEFAULT_TEST_MODEL));
        if (model.toLowerCase(Locale.ROOT).startsWith("gemini-")) {
            return testGemini(account, request, writer);
        }
        return testClaude(account, request, writer);
    }

    @Transactional
    public void recoverAfterSuccessfulTest(long accountId) {
        AdminAccountResponse current = accountService.getAccount(accountId);
        if ("error".equals(current.status())) {
            accountRepository.clearError(accountId);
        }
        accountRepository.clearRateLimit(accountId);
        accountRepository.clearAntigravityQuotaScopes(accountId);
        accountRepository.clearModelRateLimits(accountId);
        accountRepository.clearTempUnschedulable(accountId);
    }

    private HttpRequest buildGeminiApiKeyRequest(AdminAccountResponse account, String model, byte[] payload) {
        String apiKey = stringValue(account.credentials(), "api_key");
        if (apiKey == null) {
            throw new IllegalArgumentException("No API key available");
        }
        String baseUrl = resolveGeminiBaseUrl(account, GEMINI_DEFAULT_BASE_URL);
        String url = trimTrailingSlash(baseUrl) + "/v1beta/models/" + urlEncodePath(model) + ":streamGenerateContent?alt=sse";
        return newRequestBuilder(account, url)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
    }

    private HttpRequest buildGeminiOAuthRequest(AdminAccountResponse account, String model, byte[] payload) {
        String accessToken = refreshGeminiAccessTokenIfNeeded(account);
        String projectId = stringValue(account.credentials(), "project_id");
        if (projectId == null) {
            String baseUrl = resolveGeminiBaseUrl(account, GEMINI_DEFAULT_BASE_URL);
            String url = trimTrailingSlash(baseUrl) + "/v1beta/models/" + urlEncodePath(model) + ":streamGenerateContent?alt=sse";
            return newRequestBuilder(account, url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
        }
        String url = trimTrailingSlash(GEMINI_CODE_ASSIST_BASE_URL) + "/v1internal:streamGenerateContent?alt=sse";
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("model", model);
        wrapped.put("project", projectId);
        wrapped.put("request", readJsonObject(payload));
        return newRequestBuilder(account, url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", GEMINI_CLI_USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(wrapped), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest buildGeminiServiceAccountRequest(AdminAccountResponse account, String model, byte[] payload) {
        String accessToken = exchangeVertexServiceAccountAccessToken(account);
        String url = buildVertexGeminiUrl(
                resolveVertexProjectId(account),
                resolveVertexLocation(account, model),
                model,
                "streamGenerateContent"
        );
        HttpRequest.Builder builder = newRequestBuilder(account, url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        String customUserAgent = stringValue(account.credentials(), "user_agent");
        if (customUserAgent != null) {
            builder.header("User-Agent", customUserAgent);
        }
        return builder.build();
    }

    private boolean processClaudeStream(InputStream body, SseWriter writer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String payload = stripSseDataPrefix(trimmed);
                if ("[DONE]".equals(payload)) {
                    return true;
                }
                JsonNode root = parseJsonQuietly(payload);
                if (root == null) {
                    continue;
                }
                String type = root.path("type").asText("");
                if ("content_block_delta".equals(type)) {
                    String text = root.path("delta").path("text").asText("");
                    if (!text.isEmpty()) {
                        writer.send(AccountTestEvent.content(text));
                    }
                } else if ("message_stop".equals(type)) {
                    return true;
                } else if ("error".equals(type)) {
                    writer.send(AccountTestEvent.error(readErrorMessage(root)));
                    return false;
                }
            }
        }
        return true;
    }

    private boolean processOpenAiStream(InputStream body, SseWriter writer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String payload = stripSseDataPrefix(trimmed);
                if ("[DONE]".equals(payload)) {
                    return true;
                }
                JsonNode root = parseJsonQuietly(payload);
                if (root == null) {
                    continue;
                }
                String type = root.path("type").asText("");
                if ("response.output_text.delta".equals(type)) {
                    String text = root.path("delta").asText("");
                    if (!text.isEmpty()) {
                        writer.send(AccountTestEvent.content(text));
                    }
                } else if ("response.completed".equals(type) || "response.done".equals(type)) {
                    return true;
                } else if ("response.failed".equals(type)) {
                    writer.send(AccountTestEvent.error(readErrorMessage(root.path("response"))));
                    return false;
                } else if ("error".equals(type)) {
                    writer.send(AccountTestEvent.error(readErrorMessage(root)));
                    return false;
                }
            }
        }
        return true;
    }

    private boolean processOpenAiImageResponse(byte[] body, SseWriter writer) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        List<ImageResult> results = new ArrayList<>();
        collectImageResults(root, results);
        if (results.isEmpty()) {
            writer.send(AccountTestEvent.error("No images returned from responses API"));
            return false;
        }
        for (ImageResult result : results) {
            if (result.revisedPrompt() != null) {
                writer.send(AccountTestEvent.content(result.revisedPrompt()));
            }
            writer.send(AccountTestEvent.image("data:" + result.mimeType() + ";base64," + result.base64(), result.mimeType()));
        }
        return true;
    }

    private void collectImageResults(JsonNode node, List<ImageResult> results) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            String b64 = trimToNull(node.path("b64_json").asText(null));
            String result = trimToNull(node.path("result").asText(null));
            if (b64 != null || result != null) {
                String mimeType = defaultIfBlank(trimToNull(node.path("mime_type").asText(null)), "image/png");
                String revisedPrompt = trimToNull(node.path("revised_prompt").asText(null));
                results.add(new ImageResult(revisedPrompt, defaultIfBlank(result, b64), mimeType));
            }
            node.fields().forEachRemaining(entry -> collectImageResults(entry.getValue(), results));
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectImageResults(item, results));
        }
    }

    private boolean processGeminiStream(InputStream body, SseWriter writer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }
                String payload = stripSseDataPrefix(trimmed);
                if ("[DONE]".equals(payload)) {
                    return true;
                }
                JsonNode root = parseJsonQuietly(payload);
                if (root == null) {
                    continue;
                }
                JsonNode response = root.path("response").isMissingNode() ? root : root.path("response");
                JsonNode candidates = response.path("candidates");
                if (candidates.isArray() && !candidates.isEmpty()) {
                    JsonNode candidate = candidates.get(0);
                    JsonNode parts = candidate.path("content").path("parts");
                    if (parts.isArray()) {
                        for (JsonNode part : parts) {
                            String text = part.path("text").asText("");
                            if (!text.isEmpty()) {
                                writer.send(AccountTestEvent.content(text));
                            }
                            JsonNode inlineData = part.path("inlineData");
                            String mimeType = trimToNull(inlineData.path("mimeType").asText(null));
                            String data = trimToNull(inlineData.path("data").asText(null));
                            if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/") && data != null) {
                                writer.send(AccountTestEvent.image("data:" + mimeType + ";base64," + data, mimeType));
                            }
                        }
                    }
                    String finishReason = trimToNull(candidate.path("finishReason").asText(null));
                    if (finishReason != null) {
                        return true;
                    }
                }
                if (response.has("error")) {
                    writer.send(AccountTestEvent.error(readErrorMessage(response)));
                    return false;
                }
            }
        }
        return true;
    }

    private Map<String, Object> createClaudePayload(String model) {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("type", "text");
        system.put("text", CLAUDE_CODE_SYSTEM_PROMPT);
        system.put("cache_control", Map.of("type", "ephemeral"));

        Map<String, Object> messageText = new LinkedHashMap<>();
        messageText.put("type", "text");
        messageText.put("text", "hi");
        messageText.put("cache_control", Map.of("type", "ephemeral"));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(messageText));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(message));
        payload.put("system", List.of(system));
        payload.put("metadata", Map.of("user_id", Base64.getUrlEncoder().withoutPadding().encodeToString((model + ":test").getBytes(StandardCharsets.UTF_8))));
        payload.put("max_tokens", 1024);
        payload.put("temperature", 1);
        payload.put("stream", true);
        return payload;
    }

    private Map<String, Object> createOpenAiPayload(String model, boolean oauth) {
        Map<String, Object> textItem = new LinkedHashMap<>();
        textItem.put("type", "input_text");
        textItem.put("text", "hi");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("content", List.of(textItem));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", List.of(content));
        payload.put("stream", true);
        payload.put("instructions", DEFAULT_OPENAI_INSTRUCTIONS);
        if (oauth) {
            payload.put("store", false);
        }
        return payload;
    }

    private Map<String, Object> createOpenAiCompactPayload(String model) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "message");
        input.put("role", "user");
        input.put("content", "Respond with OK.");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("instructions", DEFAULT_OPENAI_INSTRUCTIONS);
        payload.put("input", List.of(input));
        return payload;
    }

    private Map<String, Object> createOpenAiOAuthImagePayload(String model, String prompt) {
        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "input_text");
        textContent.put("text", prompt);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("role", "user");
        input.put("content", List.of(textContent));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "image_generation");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", List.of(input));
        payload.put("tools", List.of(tool));
        payload.put("stream", false);
        return payload;
    }

    private byte[] createGeminiPayload(String model, String prompt) throws JsonProcessingException {
        if (isGeminiImageModel(model)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", defaultIfBlank(prompt, DEFAULT_GEMINI_IMAGE_PROMPT)))
            )));
            payload.put("generationConfig", Map.of(
                    "responseModalities", List.of("TEXT", "IMAGE"),
                    "imageConfig", Map.of("aspectRatio", "1:1")
            ));
            return objectMapper.writeValueAsBytes(payload);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", defaultIfBlank(prompt, DEFAULT_GEMINI_TEXT_PROMPT)))
        )));
        payload.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", "You are a helpful AI assistant."))
        ));
        return objectMapper.writeValueAsBytes(payload);
    }

    private String exchangeVertexServiceAccountAccessToken(AdminAccountResponse account) {
        VertexServiceAccountKey key = parseVertexServiceAccountKey(account);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(1));
        String assertion;
        try {
            var jwtBuilder = Jwts.builder()
                    .issuer(key.clientEmail())
                    .claim("scope", GEMINI_VERTEX_SCOPE)
                    .audience().add(GEMINI_VERTEX_TOKEN_URL).and()
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(expiresAt));
            if (trimToNull(key.privateKeyId()) != null) {
                jwtBuilder.header().add("kid", key.privateKeyId()).and();
            }
            assertion = jwtBuilder
                    .signWith(parseRsaPrivateKey(key.privateKey()), Jwts.SIG.RS256)
                    .compact();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to sign service account assertion");
        }

        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_VERTEX_TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = send(account, request);
            byte[] body = response.body().readAllBytes();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(extractVertexTokenErrorMessage(body));
            }
            JsonNode root = objectMapper.readTree(body);
            String accessToken = textValue(root == null ? null : root.get("access_token"));
            if (accessToken.isEmpty()) {
                throw new IllegalArgumentException("Service account token response missing access_token");
            }
            return accessToken;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to parse service account token response");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Service account token request interrupted");
        }
    }

    private String extractVertexTokenErrorMessage(byte[] body) {
        String fallback = "Service account token request failed";
        if (body == null || body.length == 0) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String errorDescription = textValue(node == null ? null : node.get("error_description"));
            if (!errorDescription.isEmpty()) {
                return errorDescription;
            }
            String error = textValue(node == null ? null : node.get("error"));
            if (!error.isEmpty()) {
                return error;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private PrivateKey parseRsaPrivateKey(String pem) {
        if (trimToNull(pem) == null) {
            throw new IllegalArgumentException("service account json missing private_key");
        }
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse service account private key");
        }
    }

    private String resolveVertexProjectId(AdminAccountResponse account) {
        String projectId = stringValue(account.credentials(), "project_id");
        if (projectId != null) {
            return projectId;
        }
        return parseVertexServiceAccountKey(account).projectId();
    }

    private String resolveVertexLocation(AdminAccountResponse account, String model) {
        if (account != null && account.credentials() != null && model != null) {
            Object raw = account.credentials().get("vertex_model_locations");
            if (raw instanceof Map<?, ?> mappings) {
                Object value = mappings.get(model);
                if (value != null) {
                    String mapped = value.toString().trim();
                    if (!mapped.isEmpty()) {
                        return mapped;
                    }
                }
            }
        }
        String location = stringValue(account.credentials(), "location");
        if (location != null) {
            return location;
        }
        location = stringValue(account.credentials(), "vertex_location");
        if (location != null) {
            return location;
        }
        return GEMINI_VERTEX_DEFAULT_LOCATION;
    }

    private String buildVertexGeminiUrl(String projectId, String location, String model, String action) {
        if (trimToNull(projectId) == null) {
            throw new IllegalArgumentException("vertex project_id is required");
        }
        String normalizedLocation = trimToNull(location) == null ? GEMINI_VERTEX_DEFAULT_LOCATION : location.trim();
        if (!VERTEX_LOCATION_PATTERN.matcher(normalizedLocation).matches()) {
            throw new IllegalArgumentException("invalid vertex location: " + normalizedLocation);
        }
        if (trimToNull(model) == null) {
            throw new IllegalArgumentException("model is required");
        }
        String host = "global".equals(normalizedLocation)
                ? "aiplatform.googleapis.com"
                : normalizedLocation + "-aiplatform.googleapis.com";
        String url = "https://" + host
                + "/v1/projects/" + urlEncodePath(projectId.trim())
                + "/locations/" + urlEncodePath(normalizedLocation)
                + "/publishers/google/models/" + urlEncodePath(model.trim())
                + ":" + action;
        if ("streamGenerateContent".equals(action)) {
            url += "?alt=sse";
        }
        return url;
    }

    private String buildVertexAnthropicUrl(String projectId, String location, String model, boolean stream) {
        if (trimToNull(projectId) == null) {
            throw new IllegalArgumentException("vertex project_id is required");
        }
        String normalizedLocation = trimToNull(location) == null ? GEMINI_VERTEX_DEFAULT_LOCATION : location.trim();
        if (!VERTEX_LOCATION_PATTERN.matcher(normalizedLocation).matches()) {
            throw new IllegalArgumentException("invalid vertex location: " + normalizedLocation);
        }
        if (trimToNull(model) == null) {
            throw new IllegalArgumentException("model is required");
        }
        String action = stream ? "streamRawPredict" : "rawPredict";
        String host = "global".equals(normalizedLocation)
                ? "aiplatform.googleapis.com"
                : normalizedLocation + "-aiplatform.googleapis.com";
        return "https://" + host
                + "/v1/projects/" + urlEncodePath(projectId.trim())
                + "/locations/" + urlEncodePath(normalizedLocation)
                + "/publishers/anthropic/models/" + urlEncodePathPreservingAt(model.trim())
                + ":" + action;
    }

    private byte[] buildVertexAnthropicRequestBody(byte[] body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode)) {
                throw new IllegalArgumentException("Request body must be a JSON object");
            }
            objectNode.remove("model");
            objectNode.put("anthropic_version", VERTEX_ANTHROPIC_VERSION);
            return objectMapper.writeValueAsBytes(objectNode);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to build Vertex request body");
        }
    }

    private String normalizeVertexAnthropicModel(String model) {
        String normalized = trimToNull(model);
        if (normalized == null || VERTEX_ANTHROPIC_ALREADY_DATED_ID_PATTERN.matcher(normalized).matches()) {
            return model;
        }
        var matcher = VERTEX_ANTHROPIC_DATED_MODEL_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            return matcher.group(1) + "@" + matcher.group(2);
        }
        return model;
    }

    private VertexServiceAccountKey parseVertexServiceAccountKey(AdminAccountResponse account) {
        if (account == null || account.credentials() == null) {
            throw new IllegalArgumentException("service account credentials not configured");
        }
        String raw = stringValue(account.credentials(), "service_account_json");
        if (raw == null) {
            raw = stringValue(account.credentials(), "service_account");
        }
        if (raw == null) {
            Object nested = account.credentials().get("service_account_json");
            if (nested == null) {
                nested = account.credentials().get("service_account");
            }
            if (nested instanceof Map<?, ?>) {
                raw = writeJson(nested);
            }
        }
        if (trimToNull(raw) == null) {
            throw new IllegalArgumentException("service_account_json not found in credentials");
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            String clientEmail = textValue(node == null ? null : node.get("client_email"));
            String privateKey = textValue(node == null ? null : node.get("private_key"));
            String privateKeyId = textValue(node == null ? null : node.get("private_key_id"));
            String projectId = textValue(node == null ? null : node.get("project_id"));
            if (clientEmail.isEmpty()) {
                throw new IllegalArgumentException("service account json missing client_email");
            }
            if (privateKey.isEmpty()) {
                throw new IllegalArgumentException("service account json missing private_key");
            }
            if (projectId.isEmpty()) {
                throw new IllegalArgumentException("service account json missing project_id");
            }
            return new VertexServiceAccountKey(clientEmail, privateKey, privateKeyId, projectId);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid service account json");
        }
    }

    private HttpRequest.Builder newRequestBuilder(AdminAccountResponse account, String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT);
    }

    private HttpResponse<InputStream> send(AdminAccountResponse account, HttpRequest request) throws IOException, InterruptedException {
        return buildHttpClient(loadProxy(account)).send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpClient buildHttpClient(AdminProxyResponse proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy == null || trimToNull(proxy.host()) == null || proxy.port() <= 0) {
            return builder.build();
        }
        Proxy.Type type = proxy.protocol() != null && proxy.protocol().toLowerCase(Locale.ROOT).startsWith("socks")
                ? Proxy.Type.SOCKS
                : Proxy.Type.HTTP;
        builder.proxy(new FixedProxySelector(type, proxy.host(), proxy.port()));
        if (trimToNull(proxy.username()) != null) {
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

    private AdminProxyResponse loadProxy(AdminAccountResponse account) {
        if (account == null || account.proxy_id() == null || account.proxy_id() <= 0) {
            return null;
        }
        return proxyRepository.getProxy(account.proxy_id()).orElse(null);
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (builder == null || headers == null) {
            return;
        }
        headers.forEach(builder::header);
    }

    private String refreshGeminiAccessTokenIfNeeded(AdminAccountResponse account) {
        String accessToken = stringValue(account.credentials(), "access_token");
        if (accessToken == null) {
            throw new IllegalArgumentException("No access token available");
        }
        String expiresAt = stringValue(account.credentials(), "expires_at");
        if (expiresAt == null) {
            return accessToken;
        }
        try {
            long expiresAtEpoch = Long.parseLong(expiresAt);
            long now = System.currentTimeMillis() / 1000L;
            if (expiresAtEpoch - now > 300) {
                return accessToken;
            }
        } catch (NumberFormatException ignored) {
            return accessToken;
        }

        GeminiOAuthTokenResponse token = geminiOAuthGatewayService.refreshAccountToken(account, loadProxy(account));
        Map<String, Object> merged = new LinkedHashMap<>(account.credentials() == null ? Map.of() : account.credentials());
        merged.putAll(geminiOAuthGatewayService.buildAccountCredentials(token));
        accountRepository.updateAccountColumns(
                account.id(),
                null,
                false,
                null,
                null,
                true,
                merged,
                false,
                Map.of(),
                false,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null
        );
        return token.access_token();
    }

    private String resolveMappedModel(AdminAccountResponse account, String requestedModel) {
        Map<String, String> mapping = extractStringMap(account == null || account.credentials() == null ? null : account.credentials().get("model_mapping"));
        return resolveMappedModel(mapping, normalizeRequestedModelForLookup(account == null ? null : account.platform(), requestedModel), requestedModel);
    }

    private String resolveCompactMappedModel(AdminAccountResponse account, String requestedModel) {
        Map<String, String> mapping = extractStringMap(account == null || account.credentials() == null ? null : account.credentials().get("compact_model_mapping"));
        return resolveMappedModel(mapping, requestedModel, requestedModel);
    }

    private String resolveMappedModel(Map<String, String> mapping, String lookupModel, String fallback) {
        if (mapping == null || mapping.isEmpty()) {
            return fallback;
        }
        String exact = trimToNull(mapping.get(lookupModel));
        if (exact != null) {
            return exact;
        }
        MatchResult wildcard = resolveWildcardMatch(mapping, lookupModel);
        if (wildcard != null) {
            return wildcard.mappedModel();
        }
        return fallback;
    }

    private MatchResult resolveWildcardMatch(Map<String, String> mapping, String requestedModel) {
        MatchResult best = null;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String pattern = trimToNull(entry.getKey());
            String target = trimToNull(entry.getValue());
            if (pattern == null || target == null || !pattern.contains("*")) {
                continue;
            }
            if (!wildcardMatches(pattern, requestedModel)) {
                continue;
            }
            int score = pattern.replace("*", "").length();
            if (best == null || score > best.score()) {
                best = new MatchResult(target, score);
            }
        }
        return best;
    }

    private boolean wildcardMatches(String pattern, String value) {
        String regex = java.util.regex.Pattern.quote(pattern).replace("\\*", ".*");
        return value.matches(regex);
    }

    private String normalizeRequestedModelForLookup(String platform, String requestedModel) {
        String trimmed = trimToNull(requestedModel);
        if (trimmed == null) {
            return "";
        }
        if (!"gemini".equals(platform) && !"antigravity".equals(platform)) {
            return trimmed;
        }
        if ("gemini-3.1-pro-preview-customtools".equals(trimmed)) {
            return "gemini-3.1-pro-preview";
        }
        return trimmed;
    }

    private String normalizeClaudeModel(String model) {
        return switch (defaultIfBlank(model, CLAUDE_DEFAULT_TEST_MODEL)) {
            case "claude-sonnet-4-5" -> "claude-sonnet-4-5-20250929";
            case "claude-opus-4-5" -> "claude-opus-4-5-20251101";
            case "claude-haiku-4-5" -> "claude-haiku-4-5-20251001";
            default -> model;
        };
    }

    private boolean isApiKey(AdminAccountResponse account) {
        return "apikey".equals(nullSafe(account.type()));
    }

    private boolean isClaudeOAuthLike(AdminAccountResponse account) {
        String type = nullSafe(account.type());
        return "oauth".equals(type) || "setup-token".equals(type);
    }

    private boolean isOpenAi(AdminAccountResponse account) {
        return "openai".equals(nullSafe(account.platform()));
    }

    private boolean isOpenAiOAuth(AdminAccountResponse account) {
        String type = nullSafe(account.type());
        return isOpenAi(account) && ("oauth".equals(type) || "setup-token".equals(type));
    }

    private boolean isGemini(AdminAccountResponse account) {
        return "gemini".equals(nullSafe(account.platform()));
    }

    private boolean isAntigravity(AdminAccountResponse account) {
        return "antigravity".equals(nullSafe(account.platform()));
    }

    private boolean isGeminiImageModel(String model) {
        String normalized = nullSafe(model).toLowerCase(Locale.ROOT);
        return normalized.startsWith("gemini-") && normalized.contains("-image");
    }

    private boolean isOpenAiImageModel(String model) {
        return nullSafe(model).toLowerCase(Locale.ROOT).startsWith("gpt-image-");
    }

    private String resolveClaudeBaseUrl(AdminAccountResponse account) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null) {
            return "https://api.anthropic.com";
        }
        if (isAntigravity(account) && isApiKey(account)) {
            return trimTrailingSlash(baseUrl) + "/antigravity";
        }
        return baseUrl;
    }

    private String resolveOpenAiBaseUrl(AdminAccountResponse account) {
        if (!isApiKey(account)) {
            return OPENAI_DEFAULT_BASE_URL;
        }
        String baseUrl = stringValue(account.credentials(), "base_url");
        return defaultIfBlank(baseUrl, OPENAI_DEFAULT_BASE_URL);
    }

    private String resolveGeminiBaseUrl(AdminAccountResponse account, String defaultBaseUrl) {
        String baseUrl = stringValue(account.credentials(), "base_url");
        if (baseUrl == null) {
            return defaultBaseUrl;
        }
        if (isAntigravity(account) && isApiKey(account)) {
            return trimTrailingSlash(baseUrl) + "/antigravity";
        }
        return baseUrl;
    }

    private String buildOpenAiResponsesUrl(String baseUrl) {
        String normalized = trimTrailingSlash(defaultIfBlank(baseUrl, OPENAI_DEFAULT_BASE_URL));
        return normalized.endsWith("/v1") ? normalized + "/responses" : normalized + "/v1/responses";
    }

    private String appendOpenAiResponsesRequestPathSuffix(String baseUrl, String suffix) {
        String normalizedBase = trimTrailingSlash(baseUrl);
        String normalizedSuffix = suffix == null ? "" : suffix.trim();
        if (normalizedSuffix.isEmpty()) {
            return normalizedBase;
        }
        if (!normalizedSuffix.startsWith("/")) {
            normalizedSuffix = "/" + normalizedSuffix;
        }
        return normalizedBase + normalizedSuffix;
    }

    private String buildHttpError(HttpResponse<InputStream> response) throws IOException {
        byte[] body = response.body().readAllBytes();
        return "API returned " + response.statusCode() + ": " + new String(body, StandardCharsets.UTF_8);
    }

    private JsonNode parseJsonQuietly(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> readJsonObject(byte[] payload) {
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to decode payload");
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to encode request");
        }
    }

    private String stripSseDataPrefix(String line) {
        int index = line.indexOf(':');
        if (index < 0) {
            return line;
        }
        return line.substring(index + 1).trim();
    }

    private String textValue(JsonNode node) {
        return node == null ? "" : node.asText("").trim();
    }

    private String readErrorMessage(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return "Unknown error";
        }
        JsonNode errorNode = root.path("error");
        if (errorNode.hasNonNull("message")) {
            return errorNode.path("message").asText();
        }
        if (root.hasNonNull("message")) {
            return root.path("message").asText();
        }
        return "Unknown error";
    }

    private String extractErrorMessage(byte[] body) {
        JsonNode root = parseJsonQuietly(new String(body, StandardCharsets.UTF_8));
        if (root == null) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return readErrorMessage(root);
    }

    private String cleanErrorMessage(Exception ex) {
        if (ex instanceof HttpStatusException statusException) {
            return statusException.getMessage();
        }
        return trimToNull(ex.getMessage()) == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeTestMode(String mode) {
        return TEST_MODE_COMPACT.equalsIgnoreCase(trimToNull(mode)) ? TEST_MODE_COMPACT : TEST_MODE_DEFAULT;
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        return trimToNull(map.get(key) == null ? null : String.valueOf(map.get(key)));
    }

    private Map<String, String> extractStringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = trimToNull(String.valueOf(entry.getKey()));
            String value = trimToNull(String.valueOf(entry.getValue()));
            if (key != null && value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String compactProbeSessionId(long accountId) {
        return accountId <= 0 ? "probe_compact" : "probe_compact_" + accountId;
    }

    private String urlEncodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String urlEncodePathPreservingAt(String value) {
        return urlEncodePath(value).replace("%40", "@");
    }

    private record MatchResult(String mappedModel, int score) {
    }

    private record ImageResult(String revisedPrompt, String base64, String mimeType) {
    }

    private record VertexServiceAccountKey(
            String clientEmail,
            String privateKey,
            String privateKeyId,
            String projectId
    ) {
    }

    private static class SseWriter {
        private final OutputStream outputStream;
        private final ObjectMapper objectMapper;

        private SseWriter(OutputStream outputStream, ObjectMapper objectMapper) {
            this.outputStream = outputStream;
            this.objectMapper = objectMapper;
        }

        void send(AccountTestEvent event) throws IOException {
            outputStream.write(("data: " + objectMapper.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
            flush();
        }

        void flush() throws IOException {
            outputStream.flush();
        }
    }

    public record BackgroundTestResult(
            String status,
            String responseText,
            String errorMessage,
            long latencyMs,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    private static final class CollectingSseWriter extends SseWriter {
        private final List<AccountTestEvent> events = new ArrayList<>();

        private CollectingSseWriter(OutputStream outputStream, ObjectMapper objectMapper) {
            super(outputStream, objectMapper);
        }

        @Override
        void send(AccountTestEvent event) throws IOException {
            events.add(event);
            super.send(event);
        }

        private void capture(AccountTestEvent event) {
            events.add(event);
        }

        private String joinContent() {
            StringBuilder builder = new StringBuilder();
            for (AccountTestEvent event : events) {
                if ("content".equals(event.type()) && event.text() != null && !event.text().isBlank()) {
                    builder.append(event.text());
                }
            }
            return builder.toString();
        }

        private String errorMessage() {
            for (int i = events.size() - 1; i >= 0; i--) {
                AccountTestEvent event = events.get(i);
                if ("error".equals(event.type()) && event.error() != null && !event.error().isBlank()) {
                    return event.error();
                }
            }
            return "";
        }
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
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }
}
