package org.apiprivaterouter.javabackend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "billing.circuit-breaker")
public record BillingCircuitBreakerProperties(
        Boolean enabled,
        Integer failureThreshold,
        Integer resetTimeoutSeconds,
        Integer halfOpenRequests
) {

    public BillingCircuitBreakerProperties {
        enabled = enabled == null || enabled;
        failureThreshold = failureThreshold != null && failureThreshold > 0 ? failureThreshold : 5;
        resetTimeoutSeconds = resetTimeoutSeconds != null && resetTimeoutSeconds > 0 ? resetTimeoutSeconds : 30;
        halfOpenRequests = halfOpenRequests != null && halfOpenRequests > 0 ? halfOpenRequests : 3;
    }
}
