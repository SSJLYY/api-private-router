package org.apiprivaterouter.javabackend.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestAuthInterceptor implements HandlerInterceptor {

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
                        return;
                    }
                }
                if (jwtUserPrincipal.tokenVersion() != currentUser.tokenVersion()) {
                    return;
                }
                request.setAttribute("api-private-router.currentUser", currentUser);
            });
        }
        return true;
    }
}
