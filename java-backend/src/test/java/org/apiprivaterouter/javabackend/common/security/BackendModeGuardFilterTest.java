package org.apiprivaterouter.javabackend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Optional;
import org.apiprivaterouter.javabackend.publicsettings.model.PublicSettingsResponse;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendModeGuardFilterTest {

    private final PublicSettingsService publicSettingsService = mock(PublicSettingsService.class);
    private final AuthUserRepository authUserRepository = mock(AuthUserRepository.class);
    private final BackendModeGuardFilter filter = new BackendModeGuardFilter(publicSettingsService, authUserRepository, new ObjectMapper());

    @Test
    void blocksRegisterWhenBackendModeEnabled() throws Exception {
        when(publicSettingsService.getPublicSettings()).thenReturn(settings(true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Backend mode is active"));
    }

    @Test
    void allowsLoginWhenBackendModeEnabled() throws Exception {
        when(publicSettingsService.getPublicSettings()).thenReturn(settings(true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void blocksUserSelfServiceForNonAdmin() throws Exception {
        when(publicSettingsService.getPublicSettings()).thenReturn(settings(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/profile");
        request.setAttribute("api-private-router.currentUser", new CurrentUser(1L, "user@example.com", "user", 1L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("User self-service is disabled"));
    }

    @Test
    void allowsUserSelfServiceForAdmin() throws Exception {
        when(publicSettingsService.getPublicSettings()).thenReturn(settings(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/profile");
        request.setAttribute(AuthTokenFilter.ATTR_PRINCIPAL, new JwtUserPrincipal(1L, "admin@example.com", "admin", 1L));
        when(authUserRepository.findActiveUserById(1L)).thenReturn(Optional.of(new CurrentUser(1L, "admin@example.com", "admin", 1L)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void allowsPublicPaymentEndpointsInBackendMode() throws Exception {
        when(publicSettingsService.getPublicSettings()).thenReturn(settings(true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payment/public/orders/verify");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private PublicSettingsResponse settings(boolean backendModeEnabled) {
        return new PublicSettingsResponse(
                false, false, false, java.util.List.of(), false, false, false, false, false, "",
                "api-private-router", "", "", "", "", "", "", false, false, "", 20, java.util.List.of(10, 20, 50),
                java.util.List.of(), java.util.List.of(), false, false, false, false, false, false,
                "OIDC", false, false, false, backendModeEnabled, false, "", false, false,
                0, "", true, 60, false, false, false, false, false, false, false
        );
    }
}
