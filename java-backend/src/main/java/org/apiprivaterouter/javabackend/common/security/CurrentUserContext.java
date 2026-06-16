package org.apiprivaterouter.javabackend.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.common.api.UnauthorizedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentUserContext {

    public CurrentUser requireUser() {
        CurrentUser resolved = resolveFromRequest();
        if (resolved != null) {
            return resolved;
        }
        throw new UnauthorizedException("User not authenticated");
    }

    public CurrentUser requireAdmin() {
        CurrentUser resolved = resolveFromRequest();
        if (resolved != null && "admin".equalsIgnoreCase(resolved.role())) {
            return resolved;
        }
        throw new UnauthorizedException("Admin authentication required");
    }

    public CurrentUser getCurrent() {
        return resolveFromRequest();
    }

    private CurrentUser resolveFromRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object currentUser = request.getAttribute("api-private-router.currentUser");
        if (currentUser instanceof CurrentUser resolved) {
            return resolved;
        }
        return null;
    }
}
