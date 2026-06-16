package org.apiprivaterouter.javabackend.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.admin.compliance.service.AdminComplianceService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class AdminComplianceGuardFilter extends OncePerRequestFilter {

    private final AdminComplianceService complianceService;
    private final CurrentUserContext currentUserContext;

    public AdminComplianceGuardFilter(AdminComplianceService complianceService, CurrentUserContext currentUserContext) {
        this.complianceService = complianceService;
        this.currentUserContext = currentUserContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/admin/") && !path.startsWith("/api/v1/admin/compliance")) {
            try {
                CurrentUser admin = currentUserContext.getCurrent();
                if (admin != null && admin.isAdmin()) {
                    if (complianceService.isComplianceRequired(admin.userId())) {
                        response.setStatus(423);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":{\"code\":\"ADMIN_COMPLIANCE_ACK_REQUIRED\",\"message\":\"Admin compliance acknowledgement required\"}}");
                        return;
                    }
                }
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(AdminComplianceGuardFilter.class)
                        .debug("compliance guard check skipped: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
