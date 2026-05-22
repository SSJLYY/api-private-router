package org.apiprivaterouter.javabackend.gateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apiprivaterouter.javabackend.gateway.repository.GatewayOpenAiResponseBindingRepository;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesWebSocketService;

@Configuration
public class GatewayResponsesHybridWebSocketHandlerConfiguration {

    @Bean
    public GatewayResponsesHybridWebSocketHandler gatewayResponsesHybridWebSocketHandler(
            GatewayRuntimeService runtimeService,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiResponsesWebSocketService responsesWebSocketService,
            GatewayOpenAiResponseBindingRepository responseBindingRepository,
            ObjectMapper objectMapper
    ) {
        return new GatewayResponsesHybridWebSocketHandler(
                runtimeService,
                routingPolicy,
                responsesWebSocketService,
                responseBindingRepository,
                objectMapper
        );
    }
}
