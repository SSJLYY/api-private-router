package org.apiprivaterouter.javabackend.gateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.apiprivaterouter.javabackend.gateway.repository.GatewayOpenAiResponseBindingRepository;
import org.apiprivaterouter.javabackend.gateway.repository.GatewayOpenAiResponseBindingRepository.ResponseBindingRow;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayApiKeyRuntimeView;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayGroupSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayUserSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiFastPolicyService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesWebSocketService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayResponsesHybridWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void retriesSingleResponseCreateTurnBeforeAnyUpstreamEventIsForwarded() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession firstUpstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        RecordingWebSocketSession secondUpstream = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));
        ThrowOnceWebSocketSession firstThrowingUpstream = new ThrowOnceWebSocketSession(firstUpstream, new IOException("connection reset by peer"));
        SequenceWebSocketClient upstreamClient = new SequenceWebSocketClient(secondUpstream);

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                upstreamClient
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "captureLastClientMessage", new Class[]{TextMessage.class}, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\"}"));
        invoke(downstreamState, "captureRequestedModel", new Class[]{String.class}, "{\"type\":\"response.create\",\"model\":\"gpt-5\"}");

        putState(handler, "downstreamStates", downstream.getId(), downstreamState);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, firstThrowingUpstream, "");
        putAttachedUpstreamState(handler, firstThrowingUpstream, downstreamState, responsesService.accountFixture(), 99L, "responses", "gpt-5", "");

        invokeHandler(handler, "forwardClientTurn", new Class[]{WebSocketSession.class, downstreamState.getClass(), TextMessage.class},
                downstream, downstreamState, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\"}"));

        assertEquals(0, firstUpstream.sentTextPayloads().size());
        assertEquals(1, secondUpstream.sentTextPayloads().size());
        assertEquals("{\"type\":\"response.create\",\"model\":\"gpt-5\"}", secondUpstream.sentTextPayloads().get(0));
        assertNull(downstream.lastCloseStatus());
    }

    @Test
    void retriesWhenUpstreamClosesBeforeAnyEventIsForwarded() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession firstUpstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        RecordingWebSocketSession secondUpstream = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));
        SequenceWebSocketClient upstreamClient = new SequenceWebSocketClient(secondUpstream);

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                upstreamClient
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "captureLastClientMessage", new Class[]{TextMessage.class}, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\"}"));
        invoke(downstreamState, "captureRequestedModel", new Class[]{String.class}, "{\"type\":\"response.create\",\"model\":\"gpt-5\"}");

        putState(handler, "downstreamStates", downstream.getId(), downstreamState);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, firstUpstream, "");
        putAttachedUpstreamState(handler, firstUpstream, downstreamState, responsesService.accountFixture(), 99L, "responses", "gpt-5", "");

        Object nativeHandler = newNativeUpstreamBridgeHandler(downstream, handler);
        invoke(nativeHandler, "afterConnectionClosed", new Class[]{WebSocketSession.class, CloseStatus.class}, firstUpstream, CloseStatus.SERVER_ERROR);

        assertEquals(1, secondUpstream.sentTextPayloads().size());
        assertEquals("{\"type\":\"response.create\",\"model\":\"gpt-5\"}", secondUpstream.sentTextPayloads().get(0));
        assertNull(downstream.lastCloseStatus());
    }

    @Test
    void sendsBlockedEventBeforeClosingOnLocalPolicyViolation() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        GatewayOpenAiFastPolicyService fastPolicyService = new GatewayOpenAiFastPolicyService(null, new JsonHelper(objectMapper));
        BlockingResponsesWebSocketService responsesService = new BlockingResponsesWebSocketService(objectMapper, fastPolicyService);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\",\"service_tier\":\"priority\"}"));

        assertEquals(1, downstream.sentTextPayloads().size());
        assertEquals("error", objectMapper.readTree(downstream.sentTextPayloads().get(0)).path("type").asText());
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), downstream.lastCloseStatus().getCode());
    }

    @Test
    void detachesUpstreamAndKeepsActiveTurnDrainingWhenTerminalNotReached() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, upstream, "");
        invoke(downstreamState, "setActiveResponseId", new Class[]{String.class}, "resp_active_1");
        invoke(downstreamState, "setLastResponseId", new Class[]{String.class}, "resp_completed_prev");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                downstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );
        invoke(upstreamState, "setActiveResponseId", new Class[]{String.class}, "resp_active_1");
        invoke(upstreamState, "setLastResponseId", new Class[]{String.class}, "resp_completed_prev");

        handler.afterConnectionClosed(downstream, CloseStatus.NORMAL);

        assertTrue(upstream.isOpen());
        assertNotNull(getState(handler, "upstreamStates", upstream.getId()));
        assertTrue((Boolean) invoke(upstreamState, "detached", new Class[]{}));
        assertEquals("resp_active_1", invoke(upstreamState, "activeResponseId", new Class[]{}));
    }

    @Test
    void detachesUpstreamAndPreservesLastResponseIdAfterTerminalResponse() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, upstream, "");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                downstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );

        invokeHandler(handler, "recordUpstreamResponseEvent",
                new Class[]{WebSocketSession.class, upstreamState.getClass(), downstreamState.getClass(), String.class},
                upstream,
                upstreamState,
                downstreamState,
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_done_1\"}}");

        handler.afterConnectionClosed(downstream, CloseStatus.NORMAL);

        assertTrue(upstream.isOpen());
        assertEquals("resp_done_1", invoke(upstreamState, "lastResponseId", new Class[]{}));
        assertTrue((Boolean) invoke(upstreamState, "detached", new Class[]{}));
        assertEquals("resp_done_1", invoke(downstreamState, "lastResponseId", new Class[]{}));
    }

    @Test
    void continuationRebindsDetachedUpstreamForMatchingPreviousResponseId() throws Exception {
        RecordingWebSocketSession firstDownstream = new RecordingWebSocketSession("downstream-1", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession secondDownstream = new RecordingWebSocketSession("downstream-2", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object firstDownstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(firstDownstreamState, "attachSession", new Class[]{WebSocketSession.class}, firstDownstream);
        invoke(firstDownstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, upstream, "");
        putState(handler, "downstreamStates", firstDownstream.getId(), firstDownstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                firstDownstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );
        invokeHandler(handler, "recordUpstreamResponseEvent",
                new Class[]{WebSocketSession.class, upstreamState.getClass(), firstDownstreamState.getClass(), String.class},
                upstream,
                upstreamState,
                firstDownstreamState,
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_detach_1\"}}");
        handler.afterConnectionClosed(firstDownstream, CloseStatus.NORMAL);

        Object reboundDownstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(reboundDownstreamState, "attachSession", new Class[]{WebSocketSession.class}, secondDownstream);
        putState(handler, "downstreamStates", secondDownstream.getId(), reboundDownstreamState);

        invokeHandler(handler, "bindUpstreamIfNeeded",
                new Class[]{WebSocketSession.class, reboundDownstreamState.getClass(), String.class},
                secondDownstream,
                reboundDownstreamState,
                "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_detach_1\"}");

        assertEquals(upstream, invoke(reboundDownstreamState, "upstream", new Class[]{}));
        assertEquals("resp_detach_1", invoke(reboundDownstreamState, "lastResponseId", new Class[]{}));
        assertEquals(secondDownstream.getId(), invoke(upstreamState, "downstreamSessionId", new Class[]{}));
        assertFalse((Boolean) invoke(upstreamState, "detached", new Class[]{}));
    }

    @Test
    void setupTokenSupportsOauthSpecificResponsesWebsocketModeFlags() throws Exception {
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                new TestResponsesWebSocketService(objectMapper),
                objectMapper,
                new SequenceWebSocketClient()
        );

        AdminAccountResponse setupTokenAccount = accountWithTypeAndExtra(
                1L,
                "setup-token",
                Map.of("openai_oauth_responses_websockets_v2_mode", "passthrough")
        );

        Object mode = invokeHandler(
                handler,
                "resolveResponsesWebSocketMode",
                new Class[]{AdminAccountResponse.class},
                setupTokenAccount
        );

        assertEquals("passthrough", mode);
    }

    @Test
    void activeDetachedUpstreamDoesNotRebindBeforeTerminalCompletes() throws Exception {
        RecordingWebSocketSession firstDownstream = new RecordingWebSocketSession("downstream-1", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession secondDownstream = new RecordingWebSocketSession("downstream-2", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        RecordingWebSocketSession replacementUpstream = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(replacementUpstream)
        );

        Object firstDownstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(firstDownstreamState, "attachSession", new Class[]{WebSocketSession.class}, firstDownstream);
        invoke(firstDownstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, upstream, "");
        invoke(firstDownstreamState, "setLastResponseId", new Class[]{String.class}, "resp_completed_prev");
        invoke(firstDownstreamState, "setActiveResponseId", new Class[]{String.class}, "resp_active_2");
        putState(handler, "downstreamStates", firstDownstream.getId(), firstDownstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                firstDownstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );
        invoke(upstreamState, "setLastResponseId", new Class[]{String.class}, "resp_completed_prev");
        invoke(upstreamState, "setActiveResponseId", new Class[]{String.class}, "resp_active_2");

        handler.afterConnectionClosed(firstDownstream, CloseStatus.NORMAL);

        Object reboundDownstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(reboundDownstreamState, "attachSession", new Class[]{WebSocketSession.class}, secondDownstream);
        putState(handler, "downstreamStates", secondDownstream.getId(), reboundDownstreamState);

        invokeHandler(handler, "bindUpstreamIfNeeded",
                new Class[]{WebSocketSession.class, reboundDownstreamState.getClass(), String.class},
                secondDownstream,
                reboundDownstreamState,
                "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_completed_prev\"}");

        assertEquals(replacementUpstream, invoke(reboundDownstreamState, "upstream", new Class[]{}));
        assertNull(invoke(upstreamState, "downstreamSessionId", new Class[]{}));
        assertTrue((Boolean) invoke(upstreamState, "detached", new Class[]{}));
    }

    @Test
    void detachedUpstreamCommitsTerminalAfterDownstreamDisconnect() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, upstream, "");
        invoke(downstreamState, "setLastResponseId", new Class[]{String.class}, "resp_completed_prev");
        invoke(downstreamState, "setActiveResponseId", new Class[]{String.class}, "resp_active_3");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                downstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );
        invoke(upstreamState, "setLastResponseId", new Class[]{String.class}, "resp_completed_prev");
        invoke(upstreamState, "setActiveResponseId", new Class[]{String.class}, "resp_active_3");

        handler.afterConnectionClosed(downstream, CloseStatus.NORMAL);

        invokeHandler(handler, "recordUpstreamResponseEvent",
                new Class[]{WebSocketSession.class, upstreamState.getClass(), downstreamState.getClass(), String.class},
                upstream,
                upstreamState,
                null,
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_done_after_detach\"}}");

        assertEquals("resp_done_after_detach", invoke(upstreamState, "lastResponseId", new Class[]{}));
        assertEquals("", invoke(upstreamState, "activeResponseId", new Class[]{}));
        assertNotNull(getState(handler, "responseBindings", "99|responses|resp_done_after_detach"));
    }

    @Test
    void detachedRebindSkipsStaleUpstreamWhenProbeFails() throws Exception {
        RecordingWebSocketSession firstDownstream = new RecordingWebSocketSession("downstream-1", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession secondDownstream = new RecordingWebSocketSession("downstream-2", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession healthyReplacement = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));
        FailingPongWebSocketSession staleUpstream = new FailingPongWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(healthyReplacement)
        );

        Object firstDownstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(firstDownstreamState, "attachSession", new Class[]{WebSocketSession.class}, firstDownstream);
        invoke(firstDownstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, staleUpstream, "");
        putState(handler, "downstreamStates", firstDownstream.getId(), firstDownstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                staleUpstream,
                firstDownstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );
        invokeHandler(handler, "recordUpstreamResponseEvent",
                new Class[]{WebSocketSession.class, upstreamState.getClass(), firstDownstreamState.getClass(), String.class},
                staleUpstream,
                upstreamState,
                firstDownstreamState,
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_probe_1\"}}");
        handler.afterConnectionClosed(firstDownstream, CloseStatus.NORMAL);

        Object reboundDownstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(reboundDownstreamState, "attachSession", new Class[]{WebSocketSession.class}, secondDownstream);
        putState(handler, "downstreamStates", secondDownstream.getId(), reboundDownstreamState);

        invokeHandler(handler, "bindUpstreamIfNeeded",
                new Class[]{WebSocketSession.class, reboundDownstreamState.getClass(), String.class},
                secondDownstream,
                reboundDownstreamState,
                "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_probe_1\"}");

        assertEquals(healthyReplacement, invoke(reboundDownstreamState, "upstream", new Class[]{}));
        assertNull(getState(handler, "upstreamStates", staleUpstream.getId()));
    }

    @Test
    void currentContinuationUpstreamReconnectsWhenProbeFails() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        FailingPongWebSocketSession staleUpstream = new FailingPongWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        RecordingWebSocketSession replacementUpstream = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(replacementUpstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, staleUpstream, "resp_bound_1");
        invoke(downstreamState, "setLastResponseId", new Class[]{String.class}, "resp_bound_1");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);
        putAttachedUpstreamState(handler, staleUpstream, downstreamState, responsesService.accountFixture(), 99L, "responses", "gpt-5", "resp_bound_1");

        invokeHandler(handler, "bindUpstreamIfNeeded",
                new Class[]{WebSocketSession.class, downstreamState.getClass(), String.class},
                downstream,
                downstreamState,
                "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_bound_1\"}");

        assertEquals(replacementUpstream, invoke(downstreamState, "upstream", new Class[]{}));
        assertNull(getState(handler, "upstreamStates", staleUpstream.getId()));
    }

    @Test
    void passthroughModeDoesNotPreserveDetachedUpstreamOnClose() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper, "passthrough");
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, upstream, "");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                downstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );
        invokeHandler(handler, "recordUpstreamResponseEvent",
                new Class[]{WebSocketSession.class, upstreamState.getClass(), downstreamState.getClass(), String.class},
                upstream,
                upstreamState,
                downstreamState,
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_passthrough_1\"}}");

        handler.afterConnectionClosed(downstream, CloseStatus.NORMAL);

        assertFalse(upstream.isOpen());
        assertNull(getState(handler, "upstreamStates", upstream.getId()));
    }

    @Test
    void passthroughModeDoesNotInjectContinuationHints() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper, "passthrough");
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(upstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "setLastResponseId", new Class[]{String.class}, "resp_prev_1");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"));

        assertEquals(1, upstream.sentTextPayloads().size());
        assertFalse(upstream.sentTextPayloads().get(0).contains("previous_response_id"));
    }

    @Test
    void normalModeInjectsContinuationHintsForStoreDisabledFollowup() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(upstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "setLastResponseId", new Class[]{String.class}, "resp_prev_store_1");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\",\"store\":false,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"continue\"}]}"));

        assertEquals(1, upstream.sentTextPayloads().size());
        assertTrue(upstream.sentTextPayloads().get(0).contains("\"previous_response_id\":\"resp_prev_store_1\""));
    }

    @Test
    void normalModeInfersAnchorForStoreDisabledFunctionCallOutput() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(upstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "setLastResponseId", new Class[]{String.class}, "resp_prev_fc_1");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\",\"store\":false,\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"));

        assertEquals(1, upstream.sentTextPayloads().size());
        assertTrue(upstream.sentTextPayloads().get(0).contains("\"previous_response_id\":\"resp_prev_fc_1\""));
    }

    @Test
    void normalModeRejectsFunctionCallOutputWhenNoAnchorCanBeRecovered() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(upstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ok\"}]}"));

        assertNotNull(downstream.lastCloseStatus());
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), downstream.lastCloseStatus().getCode());
        assertTrue(upstream.sentTextPayloads().isEmpty());
    }

    @Test
    void rejectsBinaryFirstFrame() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        handler.handleBinaryMessage(downstream, new org.springframework.web.socket.BinaryMessage(new byte[]{1, 2, 3}));

        assertNotNull(downstream.lastCloseStatus());
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), downstream.lastCloseStatus().getCode());
        assertTrue(downstream.lastCloseStatus().getReason().contains("first websocket frame must be text"));
    }

    @Test
    void continuation429StaysServiceOverload() throws Exception {
        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        CloseStatus status = (CloseStatus) invokeHandler(
                handler,
                "classifyTransportCloseStatus",
                new Class[]{downstreamState.getClass(), Throwable.class},
                downstreamState,
                new IllegalStateException("upstream handshake failed with status code 429")
        );

        assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), status.getCode());
    }

    @Test
    void continuation5xxStaysServerError() throws Exception {
        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class},
                new RecordingWebSocketSession("up-x", URI.create("wss://upstream.example/v1/responses")), "resp_prev_1");
        CloseStatus status = (CloseStatus) invokeHandler(
                handler,
                "classifyTransportCloseStatus",
                new Class[]{downstreamState.getClass(), Throwable.class},
                downstreamState,
                new IllegalStateException("upstream handshake failed with status code 503")
        );

        assertEquals(CloseStatus.SERVER_ERROR.getCode(), status.getCode());
    }

    @Test
    void retriesWithoutPreviousResponseIdOnPreviousResponseNotFound() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession firstUpstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        RecordingWebSocketSession secondUpstream = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(secondUpstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        String incrementalPayload = "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_prev_1\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"delta\"}]}";
        invoke(downstreamState, "captureLastClientMessage", new Class[]{TextMessage.class}, new TextMessage(incrementalPayload));
        com.fasterxml.jackson.databind.JsonNode baseReplayInput = objectMapper.readTree("[{\"type\":\"message\",\"role\":\"user\",\"content\":\"base\"}]");
        com.fasterxml.jackson.databind.JsonNode mergedReplayInput = responsesService.buildReplayInput(baseReplayInput, incrementalPayload);
        invoke(downstreamState, "captureReplayInput", new Class[]{com.fasterxml.jackson.databind.JsonNode.class}, mergedReplayInput);
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, firstUpstream, "resp_prev_1");
        Object upstreamState = putAttachedUpstreamState(handler, firstUpstream, downstreamState, responsesService.accountFixture(), 99L, "responses", "gpt-5", "resp_prev_1");

        Object nativeHandler = newNativeUpstreamBridgeHandler(downstream, handler);
        invoke(nativeHandler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                firstUpstream,
                new TextMessage("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"code\":\"previous_response_not_found\",\"message\":\"previous response not found\"}}"));

        assertEquals(1, secondUpstream.sentTextPayloads().size());
        assertFalse(secondUpstream.sentTextPayloads().get(0).contains("previous_response_id"));
        assertTrue(secondUpstream.sentTextPayloads().get(0).contains("\"content\":\"base\""));
        assertTrue(secondUpstream.sentTextPayloads().get(0).contains("\"content\":\"delta\""));
        assertEquals(secondUpstream, invoke(downstreamState, "upstream", new Class[]{}));
        assertNull(getState(handler, "upstreamStates", firstUpstream.getId()));
        assertNotNull(upstreamState);
    }

    @Test
    void retriesWithTrimmedEncryptedReasoningOnInvalidEncryptedContent() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        RecordingWebSocketSession firstUpstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        RecordingWebSocketSession secondUpstream = new RecordingWebSocketSession("up-2", URI.create("wss://upstream.example/v1/responses"));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                objectMapper,
                new SequenceWebSocketClient(secondUpstream)
        );

        String originalPayload = "{\"type\":\"response.create\",\"model\":\"gpt-5\",\"input\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"secret\"},{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\"}]}";
        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "captureLastClientMessage", new Class[]{TextMessage.class}, new TextMessage(originalPayload));
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);
        invoke(downstreamState, "bindUpstream", new Class[]{WebSocketSession.class, String.class}, firstUpstream, "");
        putAttachedUpstreamState(handler, firstUpstream, downstreamState, responsesService.accountFixture(), 99L, "responses", "gpt-5", "");

        Object nativeHandler = newNativeUpstreamBridgeHandler(downstream, handler);
        invoke(nativeHandler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                firstUpstream,
                new TextMessage("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"code\":\"invalid_encrypted_content\",\"message\":\"invalid encrypted content\"}}"));

        assertEquals(1, secondUpstream.sentTextPayloads().size());
        assertFalse(secondUpstream.sentTextPayloads().get(0).contains("encrypted_content"));
        assertTrue(secondUpstream.sentTextPayloads().get(0).contains("\"id\":\"rs_1\""));
        assertTrue(secondUpstream.sentTextPayloads().get(0).contains("\"content\":\"hello\""));
    }

    @Test
    void terminalResponsePersistsBindingToRepository() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        downstream.getAttributes().put("gateway.openai.responses.ws.session_model", "gpt-5");
        downstream.getAttributes().put("gateway.openai.responses.ws.prompt_cache_key", "pcache-1");
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        GatewayOpenAiResponseBindingRepository bindingRepository = mock(GatewayOpenAiResponseBindingRepository.class);

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                null,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                bindingRepository,
                objectMapper,
                new SequenceWebSocketClient()
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        invoke(downstreamState, "setRequestedModel", new Class[]{String.class}, "gpt-5");
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        Object upstreamState = putAttachedUpstreamState(
                handler,
                upstream,
                downstreamState,
                responsesService.accountFixture(),
                99L,
                "responses",
                "gpt-5",
                ""
        );

        invokeHandler(handler, "recordUpstreamResponseEvent",
                new Class[]{WebSocketSession.class, upstreamState.getClass(), downstreamState.getClass(), String.class},
                upstream,
                upstreamState,
                downstreamState,
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_persist_1\"}}");

        ArgumentCaptor<ResponseBindingRow> captor = ArgumentCaptor.forClass(ResponseBindingRow.class);
        verify(bindingRepository).store(captor.capture());
        ResponseBindingRow row = captor.getValue();
        assertEquals(99L, row.apiKeyId());
        assertEquals("responses", row.routeKey());
        assertEquals("resp_persist_1", row.responseId());
        assertEquals(1L, row.accountId());
        assertEquals("gpt-5", row.requestedModel());
        assertEquals("gpt-5", row.sessionModel());
        assertEquals("pcache-1", row.promptCacheKey());
    }

    @Test
    void persistedBindingRestoresPinnedAccountAndSessionHintsWhenMemoryIsEmpty() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        downstream.getAttributes().put("api-private-router.gatewayApiKey", new org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal(
                99L, "key", "active", 7L, "u@example.com", "active", 1, "user",
                5L, "group", "openai", "none", true, true, false
        ));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        GatewayOpenAiResponseBindingRepository bindingRepository = mock(GatewayOpenAiResponseBindingRepository.class);
        GatewayRuntimeService runtimeService = mock(GatewayRuntimeService.class);

        Instant now = Instant.now();
        when(bindingRepository.find(99L, "responses", "resp_restore_1")).thenReturn(java.util.Optional.of(
                new ResponseBindingRow(99L, "responses", "resp_restore_1", 42L, "gpt-5", "gpt-5", "pcache-restore", now, now.plusSeconds(60))
        ));
        when(runtimeService.requireContextForAccount(any(), eq("openai"), eq(false), eq(42L))).thenReturn(runtimeContextFor(accountSummary(42L)));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper, accountWithId("ctx_pool", 42L));
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                runtimeService,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                bindingRepository,
                objectMapper,
                new SequenceWebSocketClient(upstream)
        );

        Object downstreamState = newDownstreamState(accountWithId("ctx_pool", 1L), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"previous_response_id\":\"resp_restore_1\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"continue\"}]}"));

        assertEquals(upstream, invoke(downstreamState, "upstream", new Class[]{}));
        assertEquals(42L, ((AdminAccountResponse) invoke(downstreamState, "account", new Class[]{})).id());
        assertEquals("resp_restore_1", invoke(downstreamState, "lastResponseId", new Class[]{}));
        assertEquals("gpt-5", downstream.getAttributes().get("gateway.openai.responses.ws.session_model"));
        assertEquals("pcache-restore", downstream.getAttributes().get("gateway.openai.responses.ws.prompt_cache_key"));
        assertEquals(1, upstream.sentTextPayloads().size());
        assertTrue(upstream.sentTextPayloads().get(0).contains("\"model\":\"gpt-5\""));
    }

    @Test
    void expiredPersistedBindingIsIgnored() throws Exception {
        RecordingWebSocketSession downstream = new RecordingWebSocketSession("downstream", URI.create("ws://localhost/v1/responses"));
        downstream.getAttributes().put("api-private-router.gatewayApiKey", new org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal(
                99L, "key", "active", 7L, "u@example.com", "active", 1, "user",
                5L, "group", "openai", "none", true, true, false
        ));
        RecordingWebSocketSession upstream = new RecordingWebSocketSession("up-1", URI.create("wss://upstream.example/v1/responses"));
        GatewayOpenAiResponseBindingRepository bindingRepository = mock(GatewayOpenAiResponseBindingRepository.class);
        GatewayRuntimeService runtimeService = mock(GatewayRuntimeService.class);

        Instant now = Instant.now();
        when(bindingRepository.find(99L, "responses", "resp_expired_1")).thenReturn(java.util.Optional.of(
                new ResponseBindingRow(99L, "responses", "resp_expired_1", 42L, "gpt-5", "gpt-5", "pcache-expired", now.minusSeconds(120), now.minusSeconds(60))
        ));

        TestResponsesWebSocketService responsesService = new TestResponsesWebSocketService(objectMapper);
        GatewayResponsesHybridWebSocketHandler handler = new GatewayResponsesHybridWebSocketHandler(
                runtimeService,
                new GatewayOpenAiAccountRoutingPolicy(),
                responsesService,
                bindingRepository,
                objectMapper,
                new SequenceWebSocketClient(upstream)
        );

        Object downstreamState = newDownstreamState(responsesService.accountFixture(), 99L, "responses");
        invoke(downstreamState, "attachSession", new Class[]{WebSocketSession.class}, downstream);
        putState(handler, "downstreamStates", downstream.getId(), downstreamState);

        invokeHandler(handler, "handleTextMessage", new Class[]{WebSocketSession.class, TextMessage.class},
                downstream, new TextMessage("{\"type\":\"response.create\",\"model\":\"gpt-5\",\"previous_response_id\":\"resp_expired_1\"}"));

        assertEquals(1L, ((AdminAccountResponse) invoke(downstreamState, "account", new Class[]{})).id());
    }

    private Object putAttachedUpstreamState(
            GatewayResponsesHybridWebSocketHandler handler,
            WebSocketSession upstream,
            Object downstreamState,
            AdminAccountResponse account,
            long apiKeyId,
            String routeKey,
            String requestedModel,
            String boundPreviousResponseId
    ) throws Exception {
        Class<?> upstreamStateClass = Class.forName("org.apiprivaterouter.javabackend.gateway.websocket.GatewayResponsesHybridWebSocketHandler$UpstreamState");
        Object upstreamState = invokeStatic(
                upstreamStateClass,
                "attached",
                new Class[]{
                        WebSocketSession.class,
                        String.class,
                        AdminAccountResponse.class,
                        long.class,
                        String.class,
                        String.class,
                        String.class
                },
                upstream,
                (String) invoke(downstreamState, "sessionId", new Class[]{}),
                account,
                apiKeyId,
                routeKey,
                requestedModel,
                boundPreviousResponseId
        );
        putState(handler, "upstreamStates", upstream.getId(), upstreamState);
        return upstreamState;
    }

    private Object newDownstreamState(AdminAccountResponse account, long apiKeyId, String routeKey) throws Exception {
        Class<?> downstreamStateClass = Class.forName("org.apiprivaterouter.javabackend.gateway.websocket.GatewayResponsesHybridWebSocketHandler$DownstreamState");
        return invokeStatic(
                downstreamStateClass,
                "javaNative",
                new Class[]{AdminAccountResponse.class, long.class, String.class},
                account,
                apiKeyId,
                routeKey
        );
    }

    private Object newNativeUpstreamBridgeHandler(WebSocketSession downstream, GatewayResponsesHybridWebSocketHandler handler) throws Exception {
        Class<?> bridgeHandlerClass = Class.forName("org.apiprivaterouter.javabackend.gateway.websocket.GatewayResponsesHybridWebSocketHandler$NativeUpstreamBridgeHandler");
        var constructor = bridgeHandlerClass.getDeclaredConstructor(WebSocketSession.class, GatewayResponsesHybridWebSocketHandler.class);
        constructor.setAccessible(true);
        return constructor.newInstance(downstream, handler);
    }

    @SuppressWarnings("unchecked")
    private void putState(GatewayResponsesHybridWebSocketHandler handler, String fieldName, String key, Object value) throws Exception {
        var field = GatewayResponsesHybridWebSocketHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<String, Object>) field.get(handler)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Object getState(GatewayResponsesHybridWebSocketHandler handler, String fieldName, String key) throws Exception {
        var field = GatewayResponsesHybridWebSocketHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Map<String, Object>) field.get(handler)).get(key);
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object invokeStatic(Class<?> target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        var method = target.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private Object invokeHandler(GatewayResponsesHybridWebSocketHandler target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        var method = GatewayResponsesHybridWebSocketHandler.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static class TestResponsesWebSocketService extends GatewayOpenAiResponsesWebSocketService {
        private final AdminAccountResponse account;

        private TestResponsesWebSocketService(ObjectMapper objectMapper) {
            this(objectMapper, new GatewayOpenAiFastPolicyService(null, new JsonHelper(objectMapper)), "ctx_pool");
        }

        private TestResponsesWebSocketService(ObjectMapper objectMapper, GatewayOpenAiFastPolicyService fastPolicyService) {
            this(objectMapper, fastPolicyService, "ctx_pool");
        }

        private TestResponsesWebSocketService(ObjectMapper objectMapper, String wsMode) {
            this(objectMapper, new GatewayOpenAiFastPolicyService(null, new JsonHelper(objectMapper)), wsMode);
        }

        private TestResponsesWebSocketService(ObjectMapper objectMapper, AdminAccountResponse account) {
            this(objectMapper, new GatewayOpenAiFastPolicyService(null, new JsonHelper(objectMapper)), account);
        }

        private TestResponsesWebSocketService(
                ObjectMapper objectMapper,
                GatewayOpenAiFastPolicyService fastPolicyService,
                String wsMode
        ) {
            super(
                    null,
                    new GatewayOpenAiResponsesService(
                            null,
                            null,
                            null,
                            fastPolicyService,
                            objectMapper,
                            new UpstreamUrlGuard(new UrlAllowlistProperties(true, null, null, null, false, false))
                    ),
                    fastPolicyService,
                    objectMapper
            );
            this.account = buildAccount(wsMode);
        }

        private TestResponsesWebSocketService(
                ObjectMapper objectMapper,
                GatewayOpenAiFastPolicyService fastPolicyService,
                AdminAccountResponse account
        ) {
            super(
                    null,
                    new GatewayOpenAiResponsesService(
                            null,
                            null,
                            null,
                            fastPolicyService,
                            objectMapper,
                            new UpstreamUrlGuard(new UrlAllowlistProperties(true, null, null, null, false, false))
                    ),
                    fastPolicyService,
                    objectMapper
            );
            this.account = account;
        }

        private AdminAccountResponse account() {
            return account;
        }

        protected AdminAccountResponse accountFixture() {
            return account;
        }

        @Override
        public AdminAccountResponse requireAccount(GatewayRuntimeContext runtimeContext) {
            return account;
        }

        @Override
        public URI buildUpstreamUri(AdminAccountResponse account, WebSocketSession session) {
            return URI.create("wss://upstream.example/v1/responses");
        }

        @Override
        public WebSocketHttpHeaders buildUpstreamHeaders(WebSocketSession session, AdminAccountResponse account) {
            return new WebSocketHttpHeaders();
        }

        @Override
        public String resolveSubProtocol(WebSocketSession session) {
            return null;
        }

        private AdminAccountResponse buildAccount(String wsMode) {
            try {
                var components = AdminAccountResponse.class.getRecordComponents();
                Class<?>[] parameterTypes = new Class<?>[components.length];
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    var component = components[i];
                    parameterTypes[i] = component.getType();
                    args[i] = defaultValue(component.getType());
                    switch (component.getName()) {
                        case "id" -> args[i] = 1L;
                        case "name" -> args[i] = "acc";
                        case "platform" -> args[i] = "openai";
                        case "type" -> args[i] = "apikey";
                        case "credentials" -> args[i] = Map.of();
                        case "extra" -> args[i] = Map.of("responses_websockets_v2_mode", wsMode);
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
                var constructor = AdminAccountResponse.class.getDeclaredConstructor(parameterTypes);
                return constructor.newInstance(args);
            } catch (Exception ex) {
                throw new IllegalStateException("failed to build account fixture", ex);
            }
        }

    }

    private static final class BlockingResponsesWebSocketService extends TestResponsesWebSocketService {
        private BlockingResponsesWebSocketService(ObjectMapper objectMapper, GatewayOpenAiFastPolicyService fastPolicyService) {
            super(objectMapper, fastPolicyService);
        }

        @Override
        public TextMessage prepareClientMessage(WebSocketSession session, String payload, AdminAccountResponse account, boolean firstFrame) {
            throw new GatewayResponsesWebSocketCloseException(
                    CloseStatus.POLICY_VIOLATION.withReason("blocked"),
                    "blocked",
                    buildBlockedEvent("blocked")
            );
        }
    }

    private static final class SequenceWebSocketClient implements WebSocketClient {
        private final List<WebSocketSession> sessions;
        private int index;

        private SequenceWebSocketClient(WebSocketSession... sessions) {
            this.sessions = List.of(sessions);
        }

        @Override
        public ListenableFuture<WebSocketSession> doHandshake(org.springframework.web.socket.WebSocketHandler webSocketHandler, WebSocketHttpHeaders headers, URI uri) {
            SettableListenableFuture<WebSocketSession> future = new SettableListenableFuture<>();
            future.set(sessions.get(index++));
            return future;
        }

        @Override
        public CompletableFuture<WebSocketSession> execute(WebSocketHandler webSocketHandler, WebSocketHttpHeaders headers, URI uri) {
            return CompletableFuture.completedFuture(sessions.get(index++));
        }

        @Override
        public ListenableFuture<WebSocketSession> doHandshake(org.springframework.web.socket.WebSocketHandler webSocketHandler, String uriTemplate, Object... uriVariables) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocketSession> execute(WebSocketHandler webSocketHandler, String uriTemplate, Object... uriVariables) {
            throw new UnsupportedOperationException();
        }
    }

    private static class RecordingWebSocketSession implements WebSocketSession {
        private final String id;
        private final URI uri;
        private final List<String> sentTextPayloads = new ArrayList<>();
        private final Map<String, Object> attributes = new HashMap<>();
        private volatile boolean open = true;
        private volatile CloseStatus lastCloseStatus;

        private RecordingWebSocketSession(String id, URI uri) {
            this.id = id;
            this.uri = uri;
        }

        private List<String> sentTextPayloads() {
            return sentTextPayloads;
        }

        private CloseStatus lastCloseStatus() {
            return lastCloseStatus;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return Collections.emptyList();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (!open) {
                throw new IOException("connection closed");
            }
            if (message instanceof TextMessage textMessage) {
                sentTextPayloads.add(textMessage.getPayload());
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            this.open = false;
            this.lastCloseStatus = status;
        }
    }

    private static final class ThrowOnceWebSocketSession extends RecordingWebSocketSession {
        private final RecordingWebSocketSession delegate;
        private final IOException firstError;
        private boolean thrown;

        private ThrowOnceWebSocketSession(RecordingWebSocketSession delegate, IOException firstError) {
            super(delegate.getId(), delegate.getUri());
            this.delegate = delegate;
            this.firstError = firstError;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (!thrown) {
                thrown = true;
                throw firstError;
            }
            delegate.sendMessage(message);
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return delegate.getHandshakeHeaders();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            delegate.close(status);
        }
    }

    private static final class FailingPongWebSocketSession extends RecordingWebSocketSession {
        private FailingPongWebSocketSession(String id, URI uri) {
            super(id, uri);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof org.springframework.web.socket.PongMessage) {
                throw new IOException("stale detached upstream");
            }
            super.sendMessage(message);
        }
    }

    private GatewayRuntimeContext runtimeContextFor(GatewayAccountSummary account) {
        return new GatewayRuntimeContext(
                new GatewayApiKeyRuntimeView(
                        99L, 7L, "key", "name", "active", 5L,
                        0.0d, 0.0d, null,
                        0.0d, 0.0d, 0.0d,
                        0.0d, 0.0d, 0.0d,
                        null, null, null,
                        null, null, null,
                        new GatewayGroupSummary(5L, "group", "openai", "none", null, null, null, true, false, "", null)
                ),
                new GatewayUserSummary(7L, "u@example.com", "user", "active", 0.0d),
                null,
                account
        );
    }

    private GatewayAccountSummary accountSummary(long accountId) {
        return new GatewayAccountSummary(accountId, "acc-" + accountId, "openai", "apikey", "active", 1, null, Map.of(), Map.of(
                "responses_websockets_v2_mode", "ctx_pool",
                "responses_websockets_v2_enabled", true
        ));
    }

    private static AdminAccountResponse accountWithId(String wsMode, long id) {
        return accountWithTypeAndExtra(id, "apikey", Map.of(
                "responses_websockets_v2_mode", wsMode,
                "responses_websockets_v2_enabled", true
        ));
    }

    private static AdminAccountResponse accountWithTypeAndExtra(long id, String type, Map<String, Object> extra) {
        try {
            var components = AdminAccountResponse.class.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var component = components[i];
                parameterTypes[i] = component.getType();
                args[i] = defaultValue(component.getType());
                switch (component.getName()) {
                    case "id" -> args[i] = id;
                    case "name" -> args[i] = "acc-" + id;
                    case "platform" -> args[i] = "openai";
                    case "type" -> args[i] = type;
                    case "credentials" -> args[i] = Map.of();
                    case "extra" -> args[i] = extra;
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
            var constructor = AdminAccountResponse.class.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(args);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to build account fixture", ex);
        }
    }

    private static Object defaultValue(Class<?> type) {
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
