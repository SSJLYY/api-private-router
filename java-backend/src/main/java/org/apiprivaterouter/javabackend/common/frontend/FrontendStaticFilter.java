package org.apiprivaterouter.javabackend.common.frontend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class FrontendStaticFilter extends OncePerRequestFilter {

    private static final List<String> BYPASS_PREFIXES = List.of(
            "/api/",
            "/v1/",
            "/v1beta/",
            "/openai/",
            "/antigravity/",
            "/backend-api/",
            "/setup/",
            "/responses",
            "/chat/",
            "/images/",
            "/actuator",
            "/health",
            "/error"
    );

    private final FrontendStaticService frontendStaticService;

    public FrontendStaticFilter(FrontendStaticService frontendStaticService) {
        this.frontendStaticService = frontendStaticService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isFrontendCandidate(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (path != null && path.contains(".")) {
            Optional<FrontendStaticService.StaticAsset> asset = frontendStaticService.loadAsset(path);
            if (asset.isPresent()) {
                writeResponse(request, response, asset.get());
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        Optional<FrontendStaticService.StaticAsset> index = frontendStaticService.loadIndexHtml();
        if (index.isPresent()) {
            writeResponse(request, response, index.get());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isFrontendCandidate(HttpServletRequest request) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String prefix : BYPASS_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private void writeResponse(
            HttpServletRequest request,
            HttpServletResponse response,
            FrontendStaticService.StaticAsset asset
    ) throws IOException {
        if (asset.html() && asset.etag() != null) {
            String ifNoneMatch = request.getHeader("If-None-Match");
            if (asset.etag().equals(ifNoneMatch)) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                response.setHeader("ETag", asset.etag());
                response.setHeader("Cache-Control", "no-cache");
                return;
            }
            response.setHeader("ETag", asset.etag());
            response.setHeader("Cache-Control", "no-cache");
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(asset.contentType());
        response.setCharacterEncoding("UTF-8");
        if (!HttpMethod.HEAD.matches(request.getMethod())) {
            byte[] content = asset.content();
            if (asset.html()) {
                String nonce = response.getHeader("Content-Security-Policy-Nonce");
                if (nonce == null || nonce.isBlank()) {
                    Object nonceAttribute = request.getAttribute("cspNonce");
                    if (nonceAttribute != null) {
                        nonce = String.valueOf(nonceAttribute);
                    }
                }
                if (nonce != null && !nonce.isBlank()) {
                    content = new String(content, java.nio.charset.StandardCharsets.UTF_8)
                            .replace(FrontendStaticService.NONCE_PLACEHOLDER, nonce)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            response.getOutputStream().write(content);
            response.flushBuffer();
        }
    }
}
