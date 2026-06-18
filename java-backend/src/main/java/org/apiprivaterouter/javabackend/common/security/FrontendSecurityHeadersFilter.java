package org.apiprivaterouter.javabackend.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class FrontendSecurityHeadersFilter extends OncePerRequestFilter {

    public static final String CSP_NONCE_REQUEST_ATTRIBUTE = "cspNonce";

    private static final String CSP_TEMPLATE = "default-src 'self'; "
            + "script-src 'self' 'nonce-%s' https://static.cloudflareinsights.com https://static.airwallex.com https://static-demo.airwallex.com; "
            + "style-src 'self' 'unsafe-inline' https://static.airwallex.com https://static-demo.airwallex.com; "
            + "img-src 'self' data: blob: https:; "
            + "font-src 'self' data:; "
            + "connect-src 'self' https: wss:; "
            + "frame-src https://checkout.airwallex.com https://checkout-demo.airwallex.com; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "object-src 'none'";

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        if (shouldAttachCsp(request)) {
            String nonce = generateNonce();
            request.setAttribute(CSP_NONCE_REQUEST_ATTRIBUTE, nonce);
            response.setHeader("Content-Security-Policy", CSP_TEMPLATE.formatted(nonce));
            response.setHeader("Content-Security-Policy-Nonce", nonce);
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldAttachCsp(HttpServletRequest request) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return false;
        }
        return !path.startsWith("/api/")
                && !path.startsWith("/v1/")
                && !path.startsWith("/v1beta/")
                && !path.startsWith("/openai/")
                && !path.startsWith("/antigravity/")
                && !path.startsWith("/backend-api/")
                && !path.startsWith("/setup/")
                && !path.startsWith("/chat/")
                && !path.startsWith("/images/")
                && !path.startsWith("/responses")
                && !path.startsWith("/actuator")
                && !path.startsWith("/error");
    }

    private String generateNonce() {
        byte[] bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
