package org.apiprivaterouter.javabackend.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthTokenFilter extends OncePerRequestFilter {

    public static final String ATTR_PRINCIPAL = "api-private-router.jwt.principal";
    private final JwtService jwtService;

    public AuthTokenFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                try {
                    request.setAttribute(ATTR_PRINCIPAL, jwtService.parseAccessToken(token));
                } catch (Exception ignored) {
                    // Keep soft-fail behavior until Java fully owns auth.
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
