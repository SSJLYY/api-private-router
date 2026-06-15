package org.apiprivaterouter.javabackend.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestAuthInterceptor.class);

    private final AuthUserRepository authUserRepository;

    public RequestAuthInterceptor(AuthUserRepository authUserRepository) {
        this.authUserRepository = authUserRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object principal = request.getAttribute(AuthTokenFilter.ATTR_PRINCIPAL);
        if (principal instanceof JwtUserPrincipal jwtUserPrincipal) {
            authUserRepository.findActiveUserById(jwtUserPrincipal.userId()).ifPresent(currentUser -> {
                if (jwtUserPrincipal.role() != null && !jwtUserPrincipal.role().isBlank()) {
                    if (!jwtUserPrincipal.role().equalsIgnoreCase(currentUser.role())) {
                        log.warn("Token role mismatch for user {}: token role='{}', db role='{}', uri='{}'",
                                jwtUserPrincipal.userId(), jwtUserPrincipal.role(), currentUser.role(), request.getRequestURI());
                        return;
                    }
                }
                if (jwtUserPrincipal.tokenVersion() != currentUser.tokenVersion()) {
                    log.warn("Token version mismatch for user {}: token version={}, db version={}, uri='{}'",
                            jwtUserPrincipal.userId(), jwtUserPrincipal.tokenVersion(), currentUser.tokenVersion(), request.getRequestURI());
                    return;
                }
                request.setAttribute("api-private-router.currentUser", currentUser);
            });
        }
        return true;
    }
}
