package org.apiprivaterouter.javabackend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;

import java.io.IOException;
import java.util.List;

@Component
public class BackendModeGuardFilter extends OncePerRequestFilter {

    private static final List<String> USER_SELF_SERVICE_PREFIXES = List.of(
            "/api/v1/user",
            "/api/v1/users/me",
            "/api/v1/keys",
            "/api/v1/api-keys",
            "/api/v1/groups",
            "/api/v1/channels",
            "/api/v1/usage",
            "/api/v1/announcements",
            "/api/v1/redeem",
            "/api/v1/subscriptions",
            "/api/v1/channel-monitors",
            "/api/v1/payment"
    );

    private static final List<String> AUTH_ALLOWLIST = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/login/2fa",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
            "/api/v1/auth/oauth/community/callback",
            "/api/v1/auth/oauth/linuxdo/callback",
            "/api/v1/auth/oauth/wechat/callback",
            "/api/v1/auth/oauth/wechat/payment/callback",
            "/api/v1/auth/oauth/oidc/callback",
            "/api/v1/auth/oauth/github/callback",
            "/api/v1/auth/oauth/google/callback",
            "/api/v1/auth/oauth/community/complete-registration",
            "/api/v1/auth/oauth/linuxdo/complete-registration",
            "/api/v1/auth/oauth/wechat/complete-registration",
            "/api/v1/auth/oauth/oidc/complete-registration",
            "/api/v1/auth/oauth/github/complete-registration",
            "/api/v1/auth/oauth/google/complete-registration",
            "/api/v1/auth/oauth/community/create-account",
            "/api/v1/auth/oauth/linuxdo/create-account",
            "/api/v1/auth/oauth/wechat/create-account",
            "/api/v1/auth/oauth/oidc/create-account",
            "/api/v1/auth/oauth/community/bind-login",
            "/api/v1/auth/oauth/linuxdo/bind-login",
            "/api/v1/auth/oauth/wechat/bind-login",
            "/api/v1/auth/oauth/oidc/bind-login"
    );

    private final PublicSettingsService publicSettingsService;
    private final AuthUserRepository authUserRepository;
    private final ObjectMapper objectMapper;

    public BackendModeGuardFilter(
            PublicSettingsService publicSettingsService,
            AuthUserRepository authUserRepository,
            ObjectMapper objectMapper
    ) {
        this.publicSettingsService = publicSettingsService;
        this.authUserRepository = authUserRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!publicSettingsService.getPublicSettings().backend_mode_enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isBackendModeBlockedAuthPath(path)) {
            writeForbidden(response, "Backend mode is active. Registration and self-service auth flows are disabled.");
            return;
        }

        if (isUserSelfServicePath(path) && !isCurrentAdmin(request)) {
            writeForbidden(response, "Backend mode is active. User self-service is disabled.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isBackendModeBlockedAuthPath(String path) {
        if (!matchesPathPrefix(path, "/api/v1/auth")) {
            return false;
        }
        if ("/api/v1/auth/me".equals(path) || "/api/v1/auth/revoke-all-sessions".equals(path) || "/api/v1/auth/oauth/bind-token".equals(path)) {
            return false;
        }
        if (AUTH_ALLOWLIST.stream().anyMatch(path::equals)) {
            return false;
        }
        return !matchesPathPrefix(path, "/api/v1/auth/oauth/pending");
    }

    private boolean isUserSelfServicePath(String path) {
        if (matchesPathPrefix(path, "/api/v1/public/leaderboard")) {
            return false;
        }
        if (matchesPathPrefix(path, "/api/v1/payment/public") || matchesPathPrefix(path, "/api/v1/payment/webhook")) {
            return false;
        }
        return USER_SELF_SERVICE_PREFIXES.stream().anyMatch(prefix -> matchesPathPrefix(path, prefix));
    }

    /**
     * Check if current user is admin.
     * Note: This filter runs before RequestAuthInterceptor, so currentUser attribute
     * may not be set yet. Falls back to database query if needed.
     * TODO: Add Redis cache for admin role lookups to avoid repeated DB queries in high-traffic scenarios.
     *       Consider using a TTL-based cache (e.g., 5 min) keyed by userId to reduce database load.
     *       Monitor cache hit rate to tune TTL appropriately.
     */
    private boolean isCurrentAdmin(HttpServletRequest request) {
        Object currentUser = request.getAttribute("api-private-router.currentUser");
        if (currentUser instanceof CurrentUser user) {
            return "admin".equalsIgnoreCase(user.role());
        }
        Object principal = request.getAttribute(AuthTokenFilter.ATTR_PRINCIPAL);
        if (principal instanceof JwtUserPrincipal jwtUserPrincipal) {
            return authUserRepository.findActiveUserById(jwtUserPrincipal.userId())
                    .map(user -> "admin".equalsIgnoreCase(user.role()))
                    .orElse(false);
        }
        return false;
    }

    private boolean matchesPathPrefix(String path, String prefix) {
        if (path.equals(prefix)) {
            return true;
        }
        return path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(403, message));
        response.flushBuffer();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }
}
