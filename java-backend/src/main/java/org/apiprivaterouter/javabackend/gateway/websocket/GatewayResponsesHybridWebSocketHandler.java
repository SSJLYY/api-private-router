package org.apiprivaterouter.javabackend.gateway.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.api.UnauthorizedException;
import org.apiprivaterouter.javabackend.gateway.repository.GatewayOpenAiResponseBindingRepository;
import org.apiprivaterouter.javabackend.gateway.repository.GatewayOpenAiResponseBindingRepository.ResponseBindingRow;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesWebSocketService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesWebSocketService.GatewayResponsesWebSocketCloseException;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class GatewayResponsesHybridWebSocketHandler extends AbstractWebSocketHandler {
    private static final long RESPONSE_BINDING_TTL_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long DETACHED_DRAIN_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final String MODE_OFF = "off";
    private static final String MODE_CTX_POOL = "ctx_pool";
    private static final String MODE_SHARED = "shared";
    private static final String MODE_DEDICATED = "dedicated";
    private static final String MODE_PASSTHROUGH = "passthrough";

    private final GatewayRuntimeService runtimeService;
    private final GatewayOpenAiAccountRoutingPolicy routingPolicy;
    private final GatewayOpenAiResponsesWebSocketService responsesWebSocketService;
    private final GatewayOpenAiResponseBindingRepository responseBindingRepository;
    private final ObjectMapper objectMapper;
    private final WebSocketClient upstreamClient;
    private final Map<String, DownstreamState> downstreamStates = new ConcurrentHashMap<>();
    private final Map<String, UpstreamState> upstreamStates = new ConcurrentHashMap<>();
    private final Map<String, ResponseBinding> responseBindings = new ConcurrentHashMap<>();

    public GatewayResponsesHybridWebSocketHandler(
            GatewayRuntimeService runtimeService,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiResponsesWebSocketService responsesWebSocketService,
            ObjectMapper objectMapper
    ) {
        this(runtimeService, routingPolicy, responsesWebSocketService, null, objectMapper, new StandardWebSocketClient());
    }

    public GatewayResponsesHybridWebSocketHandler(
            GatewayRuntimeService runtimeService,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiResponsesWebSocketService responsesWebSocketService,
            GatewayOpenAiResponseBindingRepository responseBindingRepository,
            ObjectMapper objectMapper
    ) {
        this(runtimeService, routingPolicy, responsesWebSocketService, responseBindingRepository, objectMapper, new StandardWebSocketClient());
    }

    GatewayResponsesHybridWebSocketHandler(
            GatewayRuntimeService runtimeService,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiResponsesWebSocketService responsesWebSocketService,
            ObjectMapper objectMapper,
            WebSocketClient upstreamClient
    ) {
        this(runtimeService, routingPolicy, responsesWebSocketService, null, objectMapper, upstreamClient);
    }

    GatewayResponsesHybridWebSocketHandler(
            GatewayRuntimeService runtimeService,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiResponsesWebSocketService responsesWebSocketService,
            GatewayOpenAiResponseBindingRepository responseBindingRepository,
            ObjectMapper objectMapper,
            WebSocketClient upstreamClient
    ) {
        this.runtimeService = runtimeService;
        this.routingPolicy = routingPolicy;
        this.responsesWebSocketService = responsesWebSocketService;
        this.responseBindingRepository = responseBindingRepository;
        this.objectMapper = objectMapper;
        this.upstreamClient = upstreamClient;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(requirePrincipal(session), "openai");
        if (!routingPolicy.canHandleResponsesWebSocket(runtimeContext)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("responses websocket is not supported for this account"));
            return;
        }
        GatewayApiKeyPrincipal principal = requirePrincipal(session);
        AdminAccountResponse account = responsesWebSocketService.requireAccount(runtimeContext);
        DownstreamState state = DownstreamState.javaNative(account, principal.apiKeyId(), routeKey(session));
        state.attachSession(session);
        downstreamStates.put(session.getId(), state);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        DownstreamState state = downstreamStates.get(session.getId());
        if (state == null) {
            session.close(CloseStatus.SERVER_ERROR.withReason("missing websocket state"));
            return;
        }
        try {
            String requestedPreviousResponseId = extractTextField(message.getPayload(), "previous_response_id");
            if (!requestedPreviousResponseId.isBlank()) {
                restoreContinuationContext(session, state, requestedPreviousResponseId);
            }
            boolean passthroughMode = isPassthroughMode(state.account());
            TextMessage normalized = passthroughMode
                    ? responsesWebSocketService.preparePassthroughClientMessage(
                    session,
                    message.getPayload(),
                    state.account()
            )
                    : responsesWebSocketService.prepareClientMessage(
                    session,
                    message.getPayload(),
                    state.account(),
                    state.firstClientFrame()
            );
            responsesWebSocketService.captureSessionModel(session, normalized.getPayload());
            state.captureRequestedModel(normalized.getPayload());
            state.markClientFrameSeen();
            TextMessage hinted = passthroughMode
                    ? normalized
                    : responsesWebSocketService.applyContinuationHints(normalized, state.lastResponseId());
            TextMessage effective = passthroughMode
                    ? hinted
                    : responsesWebSocketService.normalizeContinuationTurn(
                    hinted,
                    state.lastResponseId(),
                    state.replayInput(),
                    state.lastClientMessage() == null ? null : state.lastClientMessage().getPayload()
            );
            if (!passthroughMode
                    && responsesWebSocketService.requiresFunctionCallOutputAnchor(effective.getPayload())
                    && isBlank(state.lastResponseId())) {
                throw new GatewayResponsesWebSocketCloseException(
                        CloseStatus.POLICY_VIOLATION.withReason("function_call_output requires previous_response_id; please restart the conversation"),
                        "function_call_output requires previous_response_id; please restart the conversation"
                );
            }
            state.captureReplayInput(responsesWebSocketService.buildReplayInput(state.replayInput(), effective.getPayload()));
            state.captureLastClientMessage(effective);
            state.resetPreviousResponseRecovery();
            state.resetReconnectRecovery();
            state.resetUpstreamEventForwarded();
            bindUpstreamIfNeeded(session, state, effective.getPayload());
            forwardClientTurn(session, state, effective);
        } catch (GatewayResponsesWebSocketCloseException ex) {
            if (ex.clientEvent() != null && session.isOpen()) {
                session.sendMessage(ex.clientEvent());
            }
            closeJavaPair(session, ex.closeStatus());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        DownstreamState state = downstreamStates.get(session.getId());
        if (state == null) {
            session.close(CloseStatus.SERVER_ERROR.withReason("missing websocket state"));
            return;
        }
        if (state.firstClientFrame()) {
            closeJavaPair(session, CloseStatus.POLICY_VIOLATION.withReason("first websocket frame must be text"));
            return;
        }
        bindUpstreamIfNeeded(session, state, null);
        state.resetReconnectRecovery();
        state.resetUpstreamEventForwarded();
        forwardToUpstream(session, message);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        DownstreamState state = downstreamStates.get(session.getId());
        if (state == null) {
            return;
        }
        if (state.upstream() != null && state.upstream().isOpen()) {
            state.upstream().sendMessage(message);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        DownstreamState state = downstreamStates.get(session.getId());
        closeJavaPair(session, classifyTransportCloseStatus(state, exception));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        DownstreamState state = downstreamStates.remove(session.getId());
        if (state != null && state.upstream() != null) {
            WebSocketSession upstream = state.upstream();
            UpstreamState upstreamState = upstream == null ? null : upstreamStates.get(upstream.getId());
            if (upstreamState != null
                    && upstreamState.matches(state)
                    && canDetachUpstream(state.account())
                    && canPreserveDetachedUpstream(state, upstreamState)) {
                detachUpstreamForDrain(state, upstreamState);
            } else {
                removeUpstreamState(upstream, state);
                if (upstream != null && upstream.isOpen()) {
                    upstream.close(status);
                }
            }
        }
    }

    private void bindUpstreamIfNeeded(WebSocketSession downstream, DownstreamState state, String normalizedPayload) throws Exception {
        pruneExpiredBindings();
        String requestedPreviousResponseId = extractTextField(normalizedPayload, "previous_response_id");
        WebSocketSession currentUpstream = state.upstream();
        if (currentUpstream != null && currentUpstream.isOpen()) {
            if (requestedPreviousResponseId.isBlank()) {
                return;
            }
            if (requestedPreviousResponseId.equals(state.lastResponseId())
                    || requestedPreviousResponseId.equals(state.boundPreviousResponseId())) {
                if (probeCurrentUpstream(currentUpstream, state)) {
                    return;
                }
            }
            ResponseBinding binding = responseBindings.get(bindingKey(state.apiKeyId(), state.routeKey(), requestedPreviousResponseId));
            if (binding != null
                    && downstream.getId().equals(binding.downstreamSessionId())
                    && currentUpstream.getId().equals(binding.upstreamSessionId())) {
                if (probeCurrentUpstream(currentUpstream, state)) {
                    return;
                }
            }
        }
        if (!requestedPreviousResponseId.isBlank()) {
            restoreContinuationContext(downstream, state, requestedPreviousResponseId);
            if (rebindDetachedUpstream(state, requestedPreviousResponseId)) {
                return;
            }
        }
        connectUpstream(downstream, state, requestedPreviousResponseId);
    }

    private boolean probeCurrentUpstream(WebSocketSession currentUpstream, DownstreamState state) {
        if (currentUpstream == null || state == null) {
            return false;
        }
        try {
            currentUpstream.sendMessage(new PongMessage(ByteBuffer.allocate(0)));
            return true;
        } catch (Exception ex) {
            removeUpstreamState(currentUpstream, state);
            state.unbindUpstream();
            if (currentUpstream.isOpen()) {
                try {
                    currentUpstream.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException ignored) {
                }
            }
            return false;
        }
    }

    private boolean rebindDetachedUpstream(DownstreamState state, String previousResponseId) {
        if (!canDetachUpstream(state.account())) {
            return false;
        }
        ResponseBinding binding = responseBindings.get(bindingKey(state.apiKeyId(), state.routeKey(), previousResponseId));
        if (binding == null) {
            return false;
        }
        UpstreamState upstreamState = upstreamStates.get(binding.upstreamSessionId());
        if (upstreamState == null
                || !upstreamState.detached()
                || !upstreamState.matches(state)
                || !isBlank(upstreamState.activeResponseId())) {
            return false;
        }
        WebSocketSession upstreamSession = upstreamState.session();
        if (upstreamSession == null || !upstreamSession.isOpen()) {
            upstreamStates.remove(binding.upstreamSessionId(), upstreamState);
            responseBindings.remove(binding.bindingKey(), binding);
            return false;
        }
        if (!probeDetachedUpstream(upstreamSession, upstreamState, binding)) {
            return false;
        }
        upstreamState.attach(state.sessionId(), state.requestedModel(), previousResponseId);
        state.bindExistingUpstream(upstreamSession, previousResponseId);
        String committedResponseId = upstreamState.lastResponseId();
        if (!committedResponseId.isBlank()) {
            state.setLastResponseId(committedResponseId);
            state.addResponseId(committedResponseId);
        }
        String activeResponseId = upstreamState.activeResponseId();
        if (!activeResponseId.isBlank()) {
            state.setActiveResponseId(activeResponseId);
        }
        if (binding.responseId() != null && !binding.responseId().isBlank()) {
            state.addResponseId(binding.responseId());
        }
        return true;
    }

    private void restoreContinuationContext(
            WebSocketSession downstream,
            DownstreamState state,
            String previousResponseId
    ) {
        if (downstream == null || state == null || previousResponseId == null || previousResponseId.isBlank()) {
            return;
        }
        ResponseBinding binding = responseBindings.get(bindingKey(state.apiKeyId(), state.routeKey(), previousResponseId));
        if (binding != null) {
            restorePinnedAccount(downstream, state, binding.accountId());
            applyRecoveredSessionHints(downstream, state, binding.requestedModel(), binding.sessionModel(), binding.promptCacheKey());
            return;
        }
        if (responseBindingRepository == null) {
            return;
        }
        ResponseBindingRow persisted = responseBindingRepository.find(state.apiKeyId(), state.routeKey(), previousResponseId)
                .filter(row -> row.expiresAt() != null && row.expiresAt().isAfter(Instant.now()))
                .orElse(null);
        if (persisted == null) {
            return;
        }
        if (!restorePinnedAccount(downstream, state, persisted.accountId())) {
            return;
        }
        state.setLastResponseId(previousResponseId);
        state.addResponseId(previousResponseId);
        applyRecoveredSessionHints(downstream, state, persisted.requestedModel(), persisted.sessionModel(), persisted.promptCacheKey());
    }

    private boolean restorePinnedAccount(WebSocketSession downstream, DownstreamState state, long accountId) {
        if (downstream == null || state == null || accountId <= 0) {
            return false;
        }
        if (state.account() != null && state.account().id() == accountId) {
            return true;
        }
        if (runtimeService == null) {
            return false;
        }
        GatewayApiKeyPrincipal principal = requirePrincipal(downstream);
        GatewayRuntimeContext pinnedContext = runtimeService.requireContextForAccount(
                principal,
                "openai",
                isCompactRoute(state.routeKey()),
                accountId
        );
        if (pinnedContext.account() == null || !routingPolicy.canHandleResponsesWebSocket(pinnedContext)) {
            return false;
        }
        AdminAccountResponse account = responsesWebSocketService.requireAccount(pinnedContext);
        state.replaceAccount(account);
        return true;
    }

    private void applyRecoveredSessionHints(
            WebSocketSession downstream,
            DownstreamState state,
            String requestedModel,
            String sessionModel,
            String promptCacheKey
    ) {
        if (state != null && requestedModel != null && !requestedModel.isBlank()) {
            state.setRequestedModel(requestedModel);
        }
        if (downstream == null) {
            return;
        }
        if (sessionModel != null && !sessionModel.isBlank()) {
            downstream.getAttributes().put("gateway.openai.responses.ws.session_model", sessionModel.trim());
        }
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            downstream.getAttributes().put("gateway.openai.responses.ws.prompt_cache_key", promptCacheKey.trim());
        }
    }

    private boolean probeDetachedUpstream(
            WebSocketSession upstreamSession,
            UpstreamState upstreamState,
            ResponseBinding binding
    ) {
        if (upstreamSession == null || upstreamState == null || binding == null) {
            return false;
        }
        try {
            upstreamSession.sendMessage(new PongMessage(ByteBuffer.allocate(0)));
            return true;
        } catch (Exception ex) {
            upstreamStates.remove(upstreamSession.getId(), upstreamState);
            responseBindings.remove(binding.bindingKey(), binding);
            for (String bindingKey : upstreamState.bindingKeys()) {
                responseBindings.remove(bindingKey);
            }
            if (upstreamSession.isOpen()) {
                try {
                    upstreamSession.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException ignored) {
                }
            }
            return false;
        }
    }

    private void connectUpstream(WebSocketSession downstream, DownstreamState state, String previousResponseId) throws Exception {
        WebSocketSession currentUpstream = state.upstream();
        if (currentUpstream != null) {
            state.unbindUpstream();
            removeUpstreamState(currentUpstream, state);
            if (currentUpstream.isOpen()) {
                currentUpstream.close(CloseStatus.NORMAL);
            }
        }
        URI targetUri = responsesWebSocketService.buildUpstreamUri(state.account(), downstream);
        org.springframework.web.socket.WebSocketHttpHeaders upstreamHeaders =
                responsesWebSocketService.buildUpstreamHeaders(downstream, state.account());
        String subProtocol = responsesWebSocketService.resolveSubProtocol(downstream);
        if (subProtocol != null) {
            upstreamHeaders.setSecWebSocketProtocol(java.util.List.of(subProtocol));
        }
        NativeUpstreamBridgeHandler upstreamHandler = new NativeUpstreamBridgeHandler(downstream, this);
        WebSocketSession upstream;
        try {
            ListenableFuture<WebSocketSession> future = upstreamClient.doHandshake(upstreamHandler, upstreamHeaders, targetUri);
            upstream = future.completable().join();
        } catch (CompletionException ex) {
            throw handshakeFailure(state, previousResponseId, ex.getCause() == null ? ex : ex.getCause());
        } catch (RuntimeException ex) {
            throw handshakeFailure(state, previousResponseId, ex);
        }
        state.bindUpstream(upstream, previousResponseId);
        upstreamStates.put(upstream.getId(), UpstreamState.attached(
                upstream,
                downstream.getId(),
                state.account(),
                state.apiKeyId(),
                state.routeKey(),
                state.requestedModel(),
                previousResponseId
        ));
    }

    private void forwardToUpstream(WebSocketSession downstream, WebSocketMessage<?> message) throws IOException {
        DownstreamState state = downstreamStates.get(downstream.getId());
        if (state == null || state.upstream() == null || !state.upstream().isOpen()) {
            closeJavaPair(downstream, continuationCloseStatus(state));
            return;
        }
        state.upstream().sendMessage(message);
    }

    private void forwardClientTurn(WebSocketSession downstream, DownstreamState state, TextMessage message) throws Exception {
        try {
            forwardToUpstream(downstream, message);
        } catch (IOException ex) {
            if (retryTransportReconnect(downstream, state, ex)) {
                return;
            }
            throw ex;
        }
    }

    private GatewayResponsesWebSocketCloseException handshakeFailure(
            DownstreamState state,
            String previousResponseId,
            Throwable error
    ) {
        int statusCode = extractUpstreamHandshakeStatus(error);
        String acquireReason = classifyAcquireFailureReason(error);
        if ("conn_queue_full".equals(acquireReason) || statusCode == 429 || isTimeoutError(error)) {
            return new GatewayResponsesWebSocketCloseException(
                    CloseStatus.SERVICE_OVERLOAD.withReason(handshakeBusyReason(error)),
                    handshakeBusyReason(error)
            );
        }
        if (statusCode >= 500) {
            return new GatewayResponsesWebSocketCloseException(
                    CloseStatus.SERVER_ERROR.withReason("upstream websocket handshake failed"),
                    "upstream websocket handshake failed"
            );
        }
        if ("preferred_conn_unavailable".equals(acquireReason) || !isBlank(previousResponseId)) {
            return new GatewayResponsesWebSocketCloseException(
                    continuationCloseStatus(state),
                    "upstream continuation connection is unavailable; please restart the conversation"
            );
        }
        if (statusCode == 426 || "upgrade_required".equals(acquireReason)) {
            return new GatewayResponsesWebSocketCloseException(
                    CloseStatus.POLICY_VIOLATION.withReason("upstream websocket upgrade required"),
                    "upstream websocket upgrade required"
            );
        }
        if (statusCode == 401 || statusCode == 403) {
            return new GatewayResponsesWebSocketCloseException(
                    CloseStatus.POLICY_VIOLATION.withReason("upstream websocket authentication failed"),
                    "upstream websocket authentication failed"
            );
        }
        if (statusCode >= 400 && statusCode < 500) {
            return new GatewayResponsesWebSocketCloseException(
                    CloseStatus.POLICY_VIOLATION.withReason("upstream websocket handshake rejected"),
                    "upstream websocket handshake rejected"
            );
        }
        return new GatewayResponsesWebSocketCloseException(
                CloseStatus.SERVER_ERROR.withReason("upstream websocket handshake failed"),
                "upstream websocket handshake failed"
        );
    }

    private CloseStatus classifyTransportCloseStatus(DownstreamState state, Throwable error) {
        int statusCode = extractUpstreamHandshakeStatus(error);
        String acquireReason = classifyAcquireFailureReason(error);
        if ("conn_queue_full".equals(acquireReason) || statusCode == 429 || isTimeoutError(error)) {
            return CloseStatus.SERVICE_OVERLOAD.withReason(handshakeBusyReason(error));
        }
        if (statusCode >= 500) {
            return CloseStatus.SERVER_ERROR.withReason("upstream websocket handshake failed");
        }
        if ("preferred_conn_unavailable".equals(acquireReason)) {
            return continuationCloseStatus(state);
        }
        if (!isBlank(state == null ? null : state.boundPreviousResponseId())) {
            return continuationCloseStatus(state);
        }
        if (statusCode == 426 || "upgrade_required".equals(acquireReason)) {
            return CloseStatus.POLICY_VIOLATION.withReason("upstream websocket upgrade required");
        }
        if (statusCode == 401 || statusCode == 403) {
            return CloseStatus.POLICY_VIOLATION.withReason("upstream websocket authentication failed");
        }
        if (statusCode >= 400 && statusCode < 500) {
            return CloseStatus.POLICY_VIOLATION.withReason("upstream websocket transport rejected");
        }
        return CloseStatus.SERVER_ERROR;
    }

    private CloseStatus continuationCloseStatus(DownstreamState state) {
        if (state != null && !isBlank(state.boundPreviousResponseId())) {
            return CloseStatus.POLICY_VIOLATION.withReason("upstream continuation connection is unavailable; please restart the conversation");
        }
        return CloseStatus.SESSION_NOT_RELIABLE;
    }

    private int extractUpstreamHandshakeStatus(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                int status = extractStatusCodeFromText(message);
                if (status > 0) {
                    return status;
                }
            }
            if (current instanceof CompletionException || current instanceof ExecutionException) {
                current = current.getCause();
                continue;
            }
            current = current.getCause();
        }
        return 0;
    }

    private String classifyAcquireFailureReason(Throwable error) {
        int statusCode = extractUpstreamHandshakeStatus(error);
        if (statusCode == 426) {
            return "upgrade_required";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "auth_failed";
        }
        if (statusCode == 429) {
            return "upstream_rate_limited";
        }
        if (statusCode >= 500) {
            return "upstream_5xx";
        }
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.trim().toLowerCase(Locale.ROOT);
                if (normalized.contains("preferred") && normalized.contains("connection") && normalized.contains("unavailable")) {
                    return "preferred_conn_unavailable";
                }
                if ((normalized.contains("queue") && normalized.contains("full"))
                        || (normalized.contains("busy") && normalized.contains("retry later"))) {
                    return "conn_queue_full";
                }
                if (normalized.contains("upgrade required") || normalized.contains("status 426")) {
                    return "upgrade_required";
                }
            }
            current = current.getCause();
        }
        if (isTimeoutError(error)) {
            return "acquire_timeout";
        }
        return "acquire_conn";
    }

    private int extractStatusCodeFromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(" 429") || normalized.contains("status code 429")) {
            return 429;
        }
        if (normalized.contains(" 403") || normalized.contains("status code 403")) {
            return 403;
        }
        if (normalized.contains(" 401") || normalized.contains("status code 401")) {
            return 401;
        }
        if (normalized.contains(" 400") || normalized.contains("status code 400")) {
            return 400;
        }
        if (normalized.contains(" 404") || normalized.contains("status code 404")) {
            return 404;
        }
        if (normalized.contains(" 426") || normalized.contains("status code 426")) {
            return 426;
        }
        if (normalized.contains(" 500") || normalized.contains("status code 500")) {
            return 500;
        }
        if (normalized.contains(" 502") || normalized.contains("status code 502")) {
            return 502;
        }
        if (normalized.contains(" 503") || normalized.contains("status code 503")) {
            return 503;
        }
        if (normalized.contains(" 504") || normalized.contains("status code 504")) {
            return 504;
        }
        return 0;
    }

    private boolean isTimeoutError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.trim().toLowerCase(Locale.ROOT);
                if (normalized.contains("timed out") || normalized.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String handshakeBusyReason(Throwable error) {
        return isTimeoutError(error)
                ? "upstream websocket connect timeout"
                : "upstream websocket is busy, please retry later";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void closeJavaPair(WebSocketSession downstream, CloseStatus status) throws IOException {
        DownstreamState state = downstreamStates.remove(downstream.getId());
        if (downstream.isOpen()) {
            downstream.close(status);
        }
        if (state == null) {
            return;
        }
        if (state.upstream() != null) {
            removeUpstreamState(state.upstream(), state);
            if (state.upstream().isOpen()) {
                state.upstream().close(status);
            }
        }
    }

    private boolean retryWithoutPreviousResponseId(
            WebSocketSession downstream,
            DownstreamState state,
            boolean invalidEncryptedContent
    ) throws Exception {
        TextMessage lastClientMessage = state.lastClientMessage();
        if (lastClientMessage == null || state.previousResponseRecoveryAttempted() || state.upstreamEventForwarded()) {
            return false;
        }
        boolean hasFunctionCallOutput = responsesWebSocketService.hasFunctionCallOutput(lastClientMessage.getPayload());
        if (hasFunctionCallOutput) {
            return false;
        }
        String previousResponseId = extractTextField(lastClientMessage.getPayload(), "previous_response_id");
        TextMessage retryMessage = invalidEncryptedContent
                ? responsesWebSocketService.trimEncryptedReasoningItems(lastClientMessage)
                : lastClientMessage;
        if (invalidEncryptedContent && retryMessage.getPayload().equals(lastClientMessage.getPayload())) {
            return false;
        }
        if (previousResponseId.isBlank() && !invalidEncryptedContent) {
            return false;
        }
        if (!previousResponseId.isBlank()) {
            TextMessage droppedPreviousResponseIdMessage = responsesWebSocketService.dropPreviousResponseId(retryMessage);
            String droppedPreviousResponseId = extractTextField(droppedPreviousResponseIdMessage.getPayload(), "previous_response_id");
            if (!droppedPreviousResponseId.isBlank()) {
                return false;
            }
            retryMessage = responsesWebSocketService.applyReplayInput(droppedPreviousResponseIdMessage, state.replayInput());
        }
        state.markPreviousResponseRecoveryAttempted();
        state.captureLastClientMessage(retryMessage);
        state.resetReconnectRecovery();
        state.resetUpstreamEventForwarded();
        connectUpstream(downstream, state, "");
        forwardToUpstream(downstream, retryMessage);
        return true;
    }

    private boolean retryTransportReconnect(
            WebSocketSession downstream,
            DownstreamState state,
            Throwable error
    ) throws Exception {
        if (!isRetryableTransportReconnect(state, error)) {
            return false;
        }
        TextMessage retryMessage = state.lastClientMessage();
        if (retryMessage == null) {
            return false;
        }
        state.markReconnectRecoveryAttempted();
        state.resetUpstreamEventForwarded();
        connectUpstream(downstream, state, "");
        forwardToUpstream(downstream, retryMessage);
        return true;
    }

    private boolean isRetryableTransportReconnect(DownstreamState state, Throwable error) {
        if (state == null
                || state.reconnectRecoveryAttempted()
                || state.previousResponseRecoveryAttempted()
                || state.upstreamEventForwarded()) {
            return false;
        }
        TextMessage lastClientMessage = state.lastClientMessage();
        if (lastClientMessage == null || lastClientMessage.getPayload() == null || lastClientMessage.getPayload().isBlank()) {
            return false;
        }
        if (responsesWebSocketService.hasFunctionCallOutput(lastClientMessage.getPayload())) {
            return false;
        }
        if (!extractTextField(lastClientMessage.getPayload(), "previous_response_id").isBlank()) {
            return false;
        }
        String eventType = extractEventType(lastClientMessage.getPayload());
        if (!"response.create".equals(eventType)) {
            return false;
        }
        return isRetryableTransportError(error);
    }

    private boolean isRetryableTransportError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.nio.channels.ClosedChannelException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.EOFException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.trim().toLowerCase(Locale.ROOT);
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("connection aborted")
                        || normalized.contains("connection closed")
                        || normalized.contains("eof")
                        || normalized.contains("timed out")
                        || normalized.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void removeUpstreamState(WebSocketSession upstream, DownstreamState state) {
        if (upstream != null) {
            UpstreamState upstreamState = upstreamStates.remove(upstream.getId());
            if (upstreamState != null) {
                for (String bindingKey : upstreamState.bindingKeys()) {
                    responseBindings.remove(bindingKey);
                }
            }
        }
        if (state == null) {
            return;
        }
        for (String responseId : state.responseIds()) {
            responseBindings.remove(bindingKey(state.apiKeyId(), state.routeKey(), responseId));
        }
        state.clearResponseIds();
        String responseId = state.lastResponseId();
        if (responseId != null && !responseId.isBlank() && responseId.equals(state.boundPreviousResponseId())) {
            state.clearBoundPreviousResponseId();
        }
        state.setActiveResponseId(null);
    }

    private void pruneExpiredBindings() {
        long now = System.currentTimeMillis();
        if (responseBindingRepository != null) {
            responseBindingRepository.deleteExpired(Instant.ofEpochMilli(now));
        }
        for (Map.Entry<String, ResponseBinding> entry : responseBindings.entrySet()) {
            ResponseBinding binding = entry.getValue();
            if (binding == null || now - binding.touchedAt() <= RESPONSE_BINDING_TTL_MILLIS) {
                continue;
            }
            responseBindings.remove(entry.getKey(), binding);
            UpstreamState upstreamState = upstreamStates.get(binding.upstreamSessionId());
            if (upstreamState != null) {
                upstreamState.removeBindingKey(binding.bindingKey());
            }
        }
        for (Map.Entry<String, UpstreamState> entry : upstreamStates.entrySet()) {
            UpstreamState upstreamState = entry.getValue();
            if (upstreamState == null) {
                continue;
            }
            WebSocketSession session = upstreamState.session();
            long detachedTtl = isBlank(upstreamState.activeResponseId())
                    ? RESPONSE_BINDING_TTL_MILLIS
                    : DETACHED_DRAIN_TTL_MILLIS;
            if (upstreamState.detached() && upstreamState.detachedForMillis(now) > detachedTtl) {
                upstreamStates.remove(entry.getKey(), upstreamState);
                for (String bindingKey : upstreamState.bindingKeys()) {
                    responseBindings.remove(bindingKey);
                }
                if (session != null && session.isOpen()) {
                    try {
                        session.close(CloseStatus.NORMAL);
                    } catch (IOException ignored) {
                    }
                }
                continue;
            }
            if (session != null && session.isOpen()) {
                continue;
            }
            upstreamStates.remove(entry.getKey(), upstreamState);
            for (String bindingKey : upstreamState.bindingKeys()) {
                responseBindings.remove(bindingKey);
            }
        }
    }

    private GatewayApiKeyPrincipal requirePrincipal(WebSocketSession session) {
        Object attr = session.getAttributes().get(GatewayApiKeyContextHolder.ATTR_GATEWAY_API_KEY);
        if (attr instanceof GatewayApiKeyPrincipal principal) {
            return principal;
        }
        throw new UnauthorizedException("Invalid API key");
    }

    private String extractTextField(String payload, String field) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode value = node == null ? null : node.get(field);
            return value != null && value.isTextual() ? value.asText().trim() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private String extractResponseId(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode type = node == null ? null : node.get("type");
            if (type == null || !type.isTextual()) {
                return "";
            }
            String eventType = type.asText().trim();
            if (!eventMayContainResponseId(eventType)) {
                return "";
            }
            JsonNode response = node.get("response");
            if (response != null && response.isObject()) {
                JsonNode responseId = response.get("id");
                if (responseId != null && responseId.isTextual() && !responseId.asText().isBlank()) {
                    return responseId.asText().trim();
                }
            }
            JsonNode id = node.get("id");
            if (id != null && id.isTextual() && !id.asText().isBlank()) {
                return id.asText().trim();
            }
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    private String routeKey(WebSocketSession session) {
        URI uri = session == null ? null : session.getUri();
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath().trim().toLowerCase();
        int responsesIndex = path.lastIndexOf("/responses");
        if (responsesIndex < 0) {
            return "responses";
        }
        String suffix = path.substring(responsesIndex + "/responses".length());
        if (suffix.isEmpty() || "/".equals(suffix)) {
            return "responses";
        }
        return "responses" + suffix;
    }

    private String bindingKey(long apiKeyId, String routeKey, String responseId) {
        return apiKeyId + "|" + (routeKey == null ? "" : routeKey) + "|" + (responseId == null ? "" : responseId.trim());
    }

    private String extractEventType(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode type = node == null ? null : node.get("type");
            return type != null && type.isTextual() ? type.asText().trim() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private boolean isDedicatedMode(AdminAccountResponse account) {
        return MODE_DEDICATED.equals(resolveResponsesWebSocketMode(account));
    }

    private boolean isPassthroughMode(AdminAccountResponse account) {
        return MODE_PASSTHROUGH.equals(resolveResponsesWebSocketMode(account));
    }

    private boolean canDetachUpstream(AdminAccountResponse account) {
        return account != null && !isDedicatedMode(account) && !isPassthroughMode(account);
    }

    private boolean canPreserveDetachedUpstream(DownstreamState state, UpstreamState upstreamState) {
        return !effectiveCommittedResponseId(state, upstreamState).isBlank()
                || !effectiveActiveResponseId(state, upstreamState).isBlank();
    }

    private String effectiveCommittedResponseId(DownstreamState state, UpstreamState upstreamState) {
        String downstreamCommitted = state == null ? "" : state.lastResponseId();
        if (!downstreamCommitted.isBlank()) {
            return downstreamCommitted;
        }
        return upstreamState == null ? "" : upstreamState.lastResponseId();
    }

    private String effectiveActiveResponseId(DownstreamState state, UpstreamState upstreamState) {
        String downstreamActive = state == null || state.activeResponseId() == null ? "" : state.activeResponseId();
        if (!downstreamActive.isBlank()) {
            return downstreamActive;
        }
        return upstreamState == null || upstreamState.activeResponseId() == null ? "" : upstreamState.activeResponseId();
    }

    private void detachUpstreamForDrain(DownstreamState state, UpstreamState upstreamState) {
        if (state == null || upstreamState == null) {
            return;
        }
        String committedResponseId = effectiveCommittedResponseId(state, upstreamState);
        String activeResponseId = effectiveActiveResponseId(state, upstreamState);
        if (!committedResponseId.isBlank()) {
            state.setLastResponseId(committedResponseId);
            upstreamState.setLastResponseId(committedResponseId);
        }
        upstreamState.detach();
        state.unbindUpstream();
        state.setActiveResponseId(null);
        upstreamState.setActiveResponseId(activeResponseId);
    }

    private String resolveResponsesWebSocketMode(AdminAccountResponse account) {
        if (account == null || account.extra() == null) {
            return MODE_CTX_POOL;
        }
        Map<String, Object> extra = account.extra();
        if (isOauthLikeType(account.type())) {
            String oauthMode = normalizeMode(extra.get("openai_oauth_responses_websockets_v2_mode"));
            if (!oauthMode.isEmpty()) {
                return oauthMode;
            }
            if (extra.get("openai_oauth_responses_websockets_v2_enabled") instanceof Boolean enabled) {
                return enabled ? MODE_CTX_POOL : MODE_OFF;
            }
        }
        if ("apikey".equalsIgnoreCase(account.type())) {
            String apiKeyMode = normalizeMode(extra.get("openai_apikey_responses_websockets_v2_mode"));
            if (!apiKeyMode.isEmpty()) {
                return apiKeyMode;
            }
            if (extra.get("openai_apikey_responses_websockets_v2_enabled") instanceof Boolean enabled) {
                return enabled ? MODE_CTX_POOL : MODE_OFF;
            }
        }
        String mode = normalizeMode(extra.get("responses_websockets_v2_mode"));
        if (!mode.isEmpty()) {
            return mode;
        }
        if (extra.get("responses_websockets_v2_enabled") instanceof Boolean enabled) {
            return enabled ? MODE_CTX_POOL : MODE_OFF;
        }
        if (extra.get("openai_ws_enabled") instanceof Boolean enabled) {
            return enabled ? MODE_CTX_POOL : MODE_OFF;
        }
        return MODE_CTX_POOL;
    }

    private boolean isOauthLikeType(String type) {
        return "oauth".equalsIgnoreCase(type) || "setup-token".equalsIgnoreCase(type);
    }

    private String normalizeMode(Object raw) {
        if (!(raw instanceof String value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case MODE_OFF, MODE_CTX_POOL, MODE_SHARED, MODE_DEDICATED, MODE_PASSTHROUGH -> normalized;
            default -> "";
        };
    }

    private boolean eventMayContainResponseId(String eventType) {
        return switch (eventType) {
            case "response.created",
                 "response.in_progress",
                 "response.completed",
                 "response.done",
                 "response.failed",
                 "response.incomplete",
                 "response.cancelled",
                 "response.canceled" -> true;
            default -> false;
        };
    }

    private boolean isTerminalBindingEvent(String eventType) {
        return switch (eventType) {
            case "response.completed",
                 "response.done",
                 "response.failed",
                 "response.incomplete",
                 "response.cancelled",
                 "response.canceled" -> true;
            default -> false;
        };
    }

    private void recordUpstreamResponseEvent(
            WebSocketSession upstreamSession,
            UpstreamState upstreamState,
            DownstreamState downstreamState,
            String payload
    ) {
        String responseId = extractResponseId(payload);
        String eventType = extractEventType(payload);
        boolean terminal = isTerminalBindingEvent(eventType);
        if (!responseId.isBlank()) {
            if (downstreamState != null) {
                downstreamState.setActiveResponseId(responseId);
            }
            if (upstreamState != null) {
                upstreamState.setActiveResponseId(responseId);
            }
        }
        if (!terminal) {
            return;
        }
        if (downstreamState != null) {
            downstreamState.setActiveResponseId(null);
        }
        if (upstreamState != null) {
            upstreamState.setActiveResponseId(null);
        }
        if (responseId.isBlank()) {
            return;
        }
        if (downstreamState != null) {
            downstreamState.setLastResponseId(responseId);
            downstreamState.addResponseId(responseId);
        }
        if (upstreamState != null) {
            upstreamState.setLastResponseId(responseId);
        }
        commitResponseBinding(upstreamSession, upstreamState, downstreamState, responseId);
    }

    private void commitResponseBinding(
            WebSocketSession upstreamSession,
            UpstreamState upstreamState,
            DownstreamState downstreamState,
            String responseId
    ) {
        if (upstreamSession == null || responseId == null || responseId.isBlank()) {
            return;
        }
        long apiKeyId = downstreamState != null
                ? downstreamState.apiKeyId()
                : upstreamState == null ? 0L : upstreamState.apiKeyId();
        String routeKey = downstreamState != null
                ? downstreamState.routeKey()
                : upstreamState == null ? "responses" : upstreamState.routeKey();
        String bindingKey = bindingKey(apiKeyId, routeKey, responseId);
        responseBindings.put(bindingKey, new ResponseBinding(
                bindingKey,
                responseId,
                upstreamSession.getId(),
                downstreamState == null ? null : downstreamState.sessionId(),
                downstreamState == null || downstreamState.account() == null ? 0L : downstreamState.account().id(),
                downstreamState == null ? "" : downstreamState.requestedModel(),
                downstreamState == null || downstreamState.session() == null ? "" : responsesWebSocketService.resolveSessionModel(downstreamState.session()),
                downstreamState == null || downstreamState.session() == null ? "" : resolvePromptCacheKey(downstreamState.session()),
                System.currentTimeMillis()
        ));
        if (upstreamState != null) {
            upstreamState.addBindingKey(bindingKey);
        }
        if (responseBindingRepository != null && downstreamState != null && downstreamState.account() != null) {
            Instant createdAt = Instant.now();
            responseBindingRepository.store(new ResponseBindingRow(
                    downstreamState.apiKeyId(),
                    routeKey,
                    responseId,
                    downstreamState.account().id(),
                    safeTrim(downstreamState.requestedModel()),
                    downstreamState.session() == null ? "" : responsesWebSocketService.resolveSessionModel(downstreamState.session()),
                    downstreamState.session() == null ? "" : resolvePromptCacheKey(downstreamState.session()),
                    createdAt,
                    createdAt.plusMillis(RESPONSE_BINDING_TTL_MILLIS)
            ));
        }
    }

    private String resolvePromptCacheKey(WebSocketSession session) {
        if (session == null) {
            return "";
        }
        Object value = session.getAttributes().get("gateway.openai.responses.ws.prompt_cache_key");
        return value instanceof String text ? text.trim() : "";
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isCompactRoute(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return false;
        }
        String normalized = routeKey.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("/compact");
    }

    private static final class NativeUpstreamBridgeHandler extends AbstractWebSocketHandler {
        private final WebSocketSession downstream;
        private final GatewayResponsesHybridWebSocketHandler owner;

        private NativeUpstreamBridgeHandler(WebSocketSession downstream, GatewayResponsesHybridWebSocketHandler owner) {
            this.downstream = downstream;
            this.owner = owner;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            UpstreamState upstreamState = owner.upstreamStates.get(session.getId());
            DownstreamState downstreamState = owner.resolveActiveDownstreamState(upstreamState);
            WebSocketSession currentDownstream = owner.resolveActiveDownstreamSession(downstreamState);
            if (downstreamState != null && currentDownstream != null && currentDownstream.isOpen()) {
                boolean hasFunctionCallOutput = downstreamState.lastClientMessage() != null
                        && owner.responsesWebSocketService.hasFunctionCallOutput(downstreamState.lastClientMessage().getPayload());
                boolean recoverablePreviousResponseNotFound = owner.responsesWebSocketService.isRecoverablePreviousResponseNotFound(
                        message.getPayload(),
                        hasFunctionCallOutput
                );
                boolean recoverableInvalidEncryptedContent = owner.responsesWebSocketService.isRecoverableInvalidEncryptedContent(
                        message.getPayload(),
                        hasFunctionCallOutput
                );
                if ((recoverablePreviousResponseNotFound || recoverableInvalidEncryptedContent)
                        && owner.retryWithoutPreviousResponseId(
                        currentDownstream,
                        downstreamState,
                        recoverableInvalidEncryptedContent
                )) {
                    return;
                }
            }
            owner.recordUpstreamResponseEvent(session, upstreamState, downstreamState, message.getPayload());
            if (downstreamState == null || currentDownstream == null || !currentDownstream.isOpen()) {
                return;
            }
            String requestedModel = downstreamState.requestedModel();
            if (upstreamState.account() != null && requestedModel != null) {
                currentDownstream.sendMessage(owner.responsesWebSocketService.rewriteUpstreamMessage(
                        message.getPayload(),
                        upstreamState.account(),
                        requestedModel
                ));
                downstreamState.markUpstreamEventForwarded();
                return;
            }
            currentDownstream.sendMessage(message);
            downstreamState.markUpstreamEventForwarded();
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
            UpstreamState upstreamState = owner.upstreamStates.get(session.getId());
            DownstreamState downstreamState = owner.resolveActiveDownstreamState(upstreamState);
            WebSocketSession currentDownstream = owner.resolveActiveDownstreamSession(downstreamState);
            if (currentDownstream != null && currentDownstream.isOpen()) {
                currentDownstream.sendMessage(message);
                if (downstreamState != null) {
                    downstreamState.markUpstreamEventForwarded();
                }
            }
        }

        @Override
        protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
            UpstreamState upstreamState = owner.upstreamStates.get(session.getId());
            DownstreamState downstreamState = owner.resolveActiveDownstreamState(upstreamState);
            WebSocketSession currentDownstream = owner.resolveActiveDownstreamSession(downstreamState);
            if (currentDownstream != null && currentDownstream.isOpen()) {
                currentDownstream.sendMessage(message);
                if (downstreamState != null) {
                    downstreamState.markUpstreamEventForwarded();
                }
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
            UpstreamState upstreamState = owner.upstreamStates.remove(session.getId());
            boolean shouldCloseDownstream = false;
            DownstreamState downstreamState = owner.resolveActiveDownstreamState(upstreamState);
            if (upstreamState != null) {
                for (String bindingKey : upstreamState.bindingKeys()) {
                    owner.responseBindings.remove(bindingKey);
                }
                if (downstreamState != null && downstreamState.upstream() == session) {
                    owner.removeUpstreamState(session, downstreamState);
                    downstreamState.unbindUpstream();
                    shouldCloseDownstream = true;
                }
            } else {
                downstreamState = owner.downstreamStates.get(downstream.getId());
                shouldCloseDownstream = downstreamState != null && downstreamState.upstream() == session;
            }
            WebSocketSession currentDownstream = upstreamState == null
                    ? downstream
                    : owner.resolveActiveDownstreamSession(downstreamState);
            if (shouldCloseDownstream
                    && downstreamState != null
                    && currentDownstream != null
                    && currentDownstream.isOpen()
                    && owner.retryTransportReconnect(currentDownstream, downstreamState, new IOException("connection closed before downstream received events"))) {
                return;
            }
            if (shouldCloseDownstream && currentDownstream != null && currentDownstream.isOpen()) {
                currentDownstream.close(status);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            UpstreamState upstreamState = owner.upstreamStates.get(session.getId());
            DownstreamState downstreamState = upstreamState == null ? owner.downstreamStates.get(downstream.getId())
                    : owner.downstreamStates.get(upstreamState.downstreamSessionId());
            WebSocketSession currentDownstream = owner.resolveActiveDownstreamSession(downstreamState);
            if (downstreamState != null
                    && downstreamState.upstream() == session
                    && currentDownstream != null
                    && currentDownstream.isOpen()) {
                if (owner.retryTransportReconnect(currentDownstream, downstreamState, exception)) {
                    return;
                }
                currentDownstream.close(owner.classifyTransportCloseStatus(downstreamState, exception));
            }
        }
    }

    private DownstreamState resolveActiveDownstreamState(UpstreamState upstreamState) {
        if (upstreamState == null || upstreamState.downstreamSessionId() == null) {
            return null;
        }
        return downstreamStates.get(upstreamState.downstreamSessionId());
    }

    private WebSocketSession resolveActiveDownstreamSession(DownstreamState state) {
        if (state == null || state.sessionId() == null) {
            return null;
        }
        return downstreamStates.containsKey(state.sessionId()) ? state.session() : null;
    }

    private record ResponseBinding(
            String bindingKey,
            String responseId,
            String upstreamSessionId,
            String downstreamSessionId,
            long accountId,
            String requestedModel,
            String sessionModel,
            String promptCacheKey,
            long touchedAt
    ) {
    }

    private static final class UpstreamState {
        private final WebSocketSession session;
        private final AdminAccountResponse account;
        private final long apiKeyId;
        private final String routeKey;
        private final java.util.Set<String> bindingKeys = ConcurrentHashMap.newKeySet();
        private volatile String downstreamSessionId;
        private volatile String requestedModel;
        private volatile String boundPreviousResponseId;
        private volatile String lastResponseId;
        private volatile String activeResponseId;
        private volatile boolean detached;
        private volatile long detachedAt;

        private UpstreamState(
                WebSocketSession session,
                String downstreamSessionId,
                AdminAccountResponse account,
                long apiKeyId,
                String routeKey,
                String requestedModel,
                String boundPreviousResponseId
        ) {
            this.session = session;
            this.downstreamSessionId = downstreamSessionId;
            this.account = account;
            this.apiKeyId = apiKeyId;
            this.routeKey = routeKey;
            this.requestedModel = requestedModel;
            this.boundPreviousResponseId = boundPreviousResponseId;
        }

        public static UpstreamState attached(
                WebSocketSession session,
                String downstreamSessionId,
                AdminAccountResponse account,
                long apiKeyId,
                String routeKey,
                String requestedModel,
                String boundPreviousResponseId
        ) {
            return new UpstreamState(session, downstreamSessionId, account, apiKeyId, routeKey, requestedModel, boundPreviousResponseId);
        }

        public boolean matches(DownstreamState downstreamState) {
            return downstreamState != null
                    && apiKeyId == downstreamState.apiKeyId()
                    && account != null
                    && downstreamState.account() != null
                    && account.id() == downstreamState.account().id()
                    && routeKey.equals(downstreamState.routeKey());
        }

        public WebSocketSession session() {
            return session;
        }

        public String downstreamSessionId() {
            return downstreamSessionId;
        }

        public AdminAccountResponse account() {
            return account;
        }

        public boolean detached() {
            return detached;
        }

        public java.util.Set<String> bindingKeys() {
            return bindingKeys;
        }

        public void addBindingKey(String bindingKey) {
            if (bindingKey != null && !bindingKey.isBlank()) {
                bindingKeys.add(bindingKey);
            }
        }

        public void removeBindingKey(String bindingKey) {
            if (bindingKey != null && !bindingKey.isBlank()) {
                bindingKeys.remove(bindingKey);
            }
        }

        public void setLastResponseId(String lastResponseId) {
            this.lastResponseId = lastResponseId;
        }

        public String lastResponseId() {
            return lastResponseId == null ? "" : lastResponseId.trim();
        }

        public void setActiveResponseId(String activeResponseId) {
            this.activeResponseId = activeResponseId == null || activeResponseId.isBlank() ? null : activeResponseId.trim();
        }

        public String activeResponseId() {
            return activeResponseId == null ? "" : activeResponseId.trim();
        }

        public void detach() {
            this.detached = true;
            this.downstreamSessionId = null;
            this.detachedAt = System.currentTimeMillis();
        }

        public void attach(String downstreamSessionId, String requestedModel, String boundPreviousResponseId) {
            this.detached = false;
            this.downstreamSessionId = downstreamSessionId;
            this.detachedAt = 0L;
            if (requestedModel != null && !requestedModel.isBlank()) {
                this.requestedModel = requestedModel;
            }
            this.boundPreviousResponseId = boundPreviousResponseId;
        }

        public long detachedForMillis(long now) {
            return detachedAt <= 0 ? 0L : Math.max(0L, now - detachedAt);
        }

        public long apiKeyId() {
            return apiKeyId;
        }

        public String routeKey() {
            return routeKey;
        }
    }

    private static final class DownstreamState {
        private volatile AdminAccountResponse account;
        private final long apiKeyId;
        private final String routeKey;
        private volatile WebSocketSession session;
        private volatile boolean clientFrameSeen;
        private volatile String requestedModel;
        private volatile WebSocketSession upstream;
        private volatile String lastResponseId;
        private volatile String activeResponseId;
        private volatile String boundPreviousResponseId;
        private volatile TextMessage lastClientMessage;
        private volatile boolean previousResponseRecoveryAttempted;
        private volatile boolean reconnectRecoveryAttempted;
        private volatile boolean upstreamEventForwarded;
        private volatile JsonNode replayInput;
        private final java.util.Set<String> responseIds = ConcurrentHashMap.newKeySet();

        private DownstreamState(AdminAccountResponse account, long apiKeyId, String routeKey, WebSocketSession session) {
            this.account = account;
            this.apiKeyId = apiKeyId;
            this.routeKey = routeKey == null ? "responses" : routeKey;
            this.session = session;
        }

        public static DownstreamState javaNative(AdminAccountResponse account, long apiKeyId, String routeKey) {
            return new DownstreamState(account, apiKeyId, routeKey, null);
        }

        public AdminAccountResponse account() {
            return account;
        }

        public void replaceAccount(AdminAccountResponse account) {
            if (account != null) {
                this.account = account;
            }
        }

        public long apiKeyId() {
            return apiKeyId;
        }

        public String routeKey() {
            return routeKey;
        }

        public WebSocketSession session() {
            return session;
        }

        public String sessionId() {
            return session == null ? null : session.getId();
        }

        public void attachSession(WebSocketSession session) {
            this.session = session;
        }

        public boolean firstClientFrame() {
            return !clientFrameSeen;
        }

        public void markClientFrameSeen() {
            this.clientFrameSeen = true;
        }

        public String requestedModel() {
            return requestedModel;
        }

        public void setRequestedModel(String requestedModel) {
            if (requestedModel != null && !requestedModel.isBlank()) {
                this.requestedModel = requestedModel.trim();
            }
        }

        public void captureRequestedModel(String payload) {
            if (payload == null || payload.isBlank()) {
                return;
            }
            int keyIndex;
            if (containsEventType(payload, "session.update")) {
                int sessionIndex = payload.indexOf("\"session\"");
                if (sessionIndex < 0) {
                    return;
                }
                keyIndex = payload.indexOf("\"model\"", sessionIndex);
            } else {
                keyIndex = payload.indexOf("\"model\"");
            }
            if (keyIndex < 0) {
                return;
            }
            int colonIndex = payload.indexOf(':', keyIndex);
            if (colonIndex < 0) {
                return;
            }
            int quoteStart = payload.indexOf('"', colonIndex + 1);
            if (quoteStart < 0) {
                return;
            }
            int quoteEnd = payload.indexOf('"', quoteStart + 1);
            if (quoteEnd <= quoteStart) {
                return;
            }
            String value = payload.substring(quoteStart + 1, quoteEnd).trim();
            if (!value.isEmpty()) {
                this.requestedModel = value;
            }
        }

        private boolean containsEventType(String payload, String eventType) {
            if (payload == null || payload.isBlank() || eventType == null || eventType.isBlank()) {
                return false;
            }
            int typeIndex = payload.indexOf("\"type\"");
            if (typeIndex < 0) {
                return false;
            }
            int colonIndex = payload.indexOf(':', typeIndex);
            if (colonIndex < 0) {
                return false;
            }
            int quoteStart = payload.indexOf('"', colonIndex + 1);
            if (quoteStart < 0) {
                return false;
            }
            int quoteEnd = payload.indexOf('"', quoteStart + 1);
            if (quoteEnd <= quoteStart) {
                return false;
            }
            return eventType.equals(payload.substring(quoteStart + 1, quoteEnd).trim());
        }

        public WebSocketSession upstream() {
            return upstream;
        }

        public void bindUpstream(WebSocketSession upstream, String boundPreviousResponseId) {
            this.upstream = upstream;
            this.boundPreviousResponseId = boundPreviousResponseId == null || boundPreviousResponseId.isBlank()
                    ? null
                    : boundPreviousResponseId.trim();
        }

        public void bindExistingUpstream(WebSocketSession upstream, String boundPreviousResponseId) {
            bindUpstream(upstream, boundPreviousResponseId);
        }

        public void unbindUpstream() {
            this.upstream = null;
            this.boundPreviousResponseId = null;
        }

        public String lastResponseId() {
            return lastResponseId;
        }

        public void setLastResponseId(String lastResponseId) {
            this.lastResponseId = lastResponseId == null || lastResponseId.isBlank() ? null : lastResponseId.trim();
        }

        public String activeResponseId() {
            return activeResponseId;
        }

        public void setActiveResponseId(String activeResponseId) {
            this.activeResponseId = activeResponseId == null || activeResponseId.isBlank() ? null : activeResponseId.trim();
        }

        public String boundPreviousResponseId() {
            return boundPreviousResponseId;
        }

        public void clearBoundPreviousResponseId() {
            this.boundPreviousResponseId = null;
        }

        public TextMessage lastClientMessage() {
            return lastClientMessage;
        }

        public void captureLastClientMessage(TextMessage lastClientMessage) {
            this.lastClientMessage = lastClientMessage;
        }

        public boolean previousResponseRecoveryAttempted() {
            return previousResponseRecoveryAttempted;
        }

        public void resetPreviousResponseRecovery() {
            this.previousResponseRecoveryAttempted = false;
        }

        public void markPreviousResponseRecoveryAttempted() {
            this.previousResponseRecoveryAttempted = true;
        }

        public boolean reconnectRecoveryAttempted() {
            return reconnectRecoveryAttempted;
        }

        public void resetReconnectRecovery() {
            this.reconnectRecoveryAttempted = false;
        }

        public void markReconnectRecoveryAttempted() {
            this.reconnectRecoveryAttempted = true;
        }

        public boolean upstreamEventForwarded() {
            return upstreamEventForwarded;
        }

        public void resetUpstreamEventForwarded() {
            this.upstreamEventForwarded = false;
        }

        public void markUpstreamEventForwarded() {
            this.upstreamEventForwarded = true;
        }

        public JsonNode replayInput() {
            return replayInput == null ? null : replayInput.deepCopy();
        }

        public void captureReplayInput(JsonNode replayInput) {
            this.replayInput = replayInput == null ? null : replayInput.deepCopy();
        }

        public void addResponseId(String responseId) {
            if (responseId != null && !responseId.isBlank()) {
                this.responseIds.add(responseId.trim());
            }
        }

        public java.util.Set<String> responseIds() {
            return responseIds;
        }

        public void clearResponseIds() {
            this.responseIds.clear();
        }
    }
}
