package org.apiprivaterouter.javabackend.common.security;

import org.apiprivaterouter.javabackend.common.config.AutoSetupProperties;
import org.apiprivaterouter.javabackend.common.config.DataDirectoryProperties;
import org.apiprivaterouter.javabackend.common.config.FrontendAssetsProperties;
import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.apiprivaterouter.javabackend.common.frontend.FrontendStaticFilter;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyAuthFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({
        JwtProperties.class,
        DataDirectoryProperties.class,
        FrontendAssetsProperties.class,
        AutoSetupProperties.class,
        UrlAllowlistProperties.class
})
public class SecurityConfig implements WebMvcConfigurer {

    private final RequestAuthInterceptor requestAuthInterceptor;
    private final AuthTokenFilter authTokenFilter;
    private final GatewayApiKeyAuthFilter gatewayApiKeyAuthFilter;
    private final ContentModerationGatewayFilter contentModerationGatewayFilter;
    private final BackendModeGuardFilter backendModeGuardFilter;
    private final FrontendSecurityHeadersFilter frontendSecurityHeadersFilter;
    private final FrontendStaticFilter frontendStaticFilter;

    public SecurityConfig(
            RequestAuthInterceptor requestAuthInterceptor,
            AuthTokenFilter authTokenFilter,
            GatewayApiKeyAuthFilter gatewayApiKeyAuthFilter,
            ContentModerationGatewayFilter contentModerationGatewayFilter,
            BackendModeGuardFilter backendModeGuardFilter,
            FrontendSecurityHeadersFilter frontendSecurityHeadersFilter,
            FrontendStaticFilter frontendStaticFilter
    ) {
        this.requestAuthInterceptor = requestAuthInterceptor;
        this.authTokenFilter = authTokenFilter;
        this.gatewayApiKeyAuthFilter = gatewayApiKeyAuthFilter;
        this.contentModerationGatewayFilter = contentModerationGatewayFilter;
        this.backendModeGuardFilter = backendModeGuardFilter;
        this.frontendSecurityHeadersFilter = frontendSecurityHeadersFilter;
        this.frontendStaticFilter = frontendStaticFilter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestAuthInterceptor);
    }

    @Bean
    public FilterRegistrationBean<AuthTokenFilter> authTokenFilterRegistration() {
        FilterRegistrationBean<AuthTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(authTokenFilter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<GatewayApiKeyAuthFilter> gatewayApiKeyAuthFilterRegistration() {
        FilterRegistrationBean<GatewayApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(gatewayApiKeyAuthFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ContentModerationGatewayFilter> contentModerationGatewayFilterRegistration() {
        FilterRegistrationBean<ContentModerationGatewayFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(contentModerationGatewayFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(3);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<BackendModeGuardFilter> backendModeGuardFilterRegistration() {
        FilterRegistrationBean<BackendModeGuardFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(backendModeGuardFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(4);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<FrontendSecurityHeadersFilter> frontendSecurityHeadersFilterRegistration() {
        FilterRegistrationBean<FrontendSecurityHeadersFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(frontendSecurityHeadersFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(5);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<FrontendStaticFilter> frontendStaticFilterRegistration() {
        FilterRegistrationBean<FrontendStaticFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(frontendStaticFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(6);
        return registration;
    }
}
