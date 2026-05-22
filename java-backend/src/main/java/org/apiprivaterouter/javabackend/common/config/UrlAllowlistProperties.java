package org.apiprivaterouter.javabackend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "security.url-allowlist")
public record UrlAllowlistProperties(
        Boolean enabled,
        List<String> upstreamHosts,
        List<String> pricingHosts,
        List<String> crsHosts,
        Boolean allowPrivateHosts,
        Boolean allowInsecureHttp
) {

    private static final List<String> DEFAULT_UPSTREAM_HOSTS = List.of(
            "api.openai.com",
            "chatgpt.com",
            "api.anthropic.com",
            "api.kimi.com",
            "open.bigmodel.cn",
            "api.minimaxi.com",
            "generativelanguage.googleapis.com",
            "cloudcode-pa.googleapis.com",
            "oauth2.googleapis.com",
            "aiplatform.googleapis.com",
            "*.aiplatform.googleapis.com",
            "*.openai.azure.com"
    );

    private static final List<String> DEFAULT_PRICING_HOSTS = List.of(
            "raw.githubusercontent.com"
    );

    public UrlAllowlistProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        upstreamHosts = copyOrDefault(upstreamHosts, DEFAULT_UPSTREAM_HOSTS);
        pricingHosts = copyOrDefault(pricingHosts, DEFAULT_PRICING_HOSTS);
        crsHosts = copyOrDefault(crsHosts, List.of());
        allowPrivateHosts = allowPrivateHosts == null ? Boolean.FALSE : allowPrivateHosts;
        allowInsecureHttp = allowInsecureHttp == null ? Boolean.FALSE : allowInsecureHttp;
    }

    private static List<String> copyOrDefault(List<String> value, List<String> fallback) {
        return value == null ? fallback : List.copyOf(value);
    }
}
