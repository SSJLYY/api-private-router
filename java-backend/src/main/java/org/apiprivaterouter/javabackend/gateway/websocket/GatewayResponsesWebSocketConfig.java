package org.apiprivaterouter.javabackend.gateway.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class GatewayResponsesWebSocketConfig implements WebSocketConfigurer {

    private final GatewayResponsesHybridWebSocketHandler webSocketHandler;
    private final GatewayResponsesHandshakeInterceptor handshakeInterceptor;

    public GatewayResponsesWebSocketConfig(
            GatewayResponsesHybridWebSocketHandler webSocketHandler,
            GatewayResponsesHandshakeInterceptor handshakeInterceptor
    ) {
        this.webSocketHandler = webSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler,
                        "/v1/responses",
                        "/responses",
                        "/openai/v1/responses",
                        "/backend-api/codex/responses")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
