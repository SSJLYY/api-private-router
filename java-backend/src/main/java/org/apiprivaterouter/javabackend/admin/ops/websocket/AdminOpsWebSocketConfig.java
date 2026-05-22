package org.apiprivaterouter.javabackend.admin.ops.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AdminOpsWebSocketConfig implements WebSocketConfigurer {

    private final AdminOpsQpsWebSocketHandler qpsWebSocketHandler;

    public AdminOpsWebSocketConfig(AdminOpsQpsWebSocketHandler qpsWebSocketHandler) {
        this.qpsWebSocketHandler = qpsWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(qpsWebSocketHandler, "/api/v1/admin/ops/ws/qps")
                .setAllowedOriginPatterns("*");
    }
}
