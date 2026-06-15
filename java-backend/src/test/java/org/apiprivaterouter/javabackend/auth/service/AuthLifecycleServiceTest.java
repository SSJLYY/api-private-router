package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.admin.subscription.repository.AdminSubscriptionRepository;
import org.apiprivaterouter.javabackend.auth.model.AuthTokenResponse;
import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.repository.AuthFlowUserRepository;
import org.apiprivaterouter.javabackend.auth.repository.AuthPublicEmailRepository;
import org.apiprivaterouter.javabackend.auth.repository.AuthRefreshTokenRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.JwtProperties;
import org.apiprivaterouter.javabackend.common.security.JwtService;
import org.apiprivaterouter.javabackend.common.security.PasswordHasher;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomEndpoint;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomMenuItem;
import org.apiprivaterouter.javabackend.publicsettings.model.PublicSettingsResponse;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpEmailService;
import org.apiprivaterouter.javabackend.usertotp.service.UserTotpService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthLifecycleServiceTest {

    private AuthPublicEmailRepository authPublicEmailRepository;
    private AuthFlowUserRepository authFlowUserRepository;
    private AuthRefreshTokenRepository authRefreshTokenRepository;
    private PublicSettingsService publicSettingsService;
    private AuthTurnstileService authTurnstileService;
    private AdminSubscriptionRepository adminSubscriptionRepository;
    private CurrentUserService currentUserService;
    private AuthLifecycleService service;

    @BeforeEach
    void setUp() {
        authPublicEmailRepository = mock(AuthPublicEmailRepository.class);
        authFlowUserRepository = mock(AuthFlowUserRepository.class);
        authRefreshTokenRepository = mock(AuthRefreshTokenRepository.class);
        publicSettingsService = mock(PublicSettingsService.class);
        authTurnstileService = mock(AuthTurnstileService.class);
        adminSubscriptionRepository = mock(AdminSubscriptionRepository.class);
        currentUserService = mock(CurrentUserService.class);

        when(publicSettingsService.getPublicSettings()).thenReturn(publicSettings());
        when(authPublicEmailRepository.getSettingValues(any())).thenReturn(Map.of());
        when(currentUserService.getCurrentUser(any())).thenAnswer(invocation -> {
            org.apiprivaterouter.javabackend.common.security.CurrentUser currentUser = invocation.getArgument(0);
            return new CurrentUserResponse(
                    currentUser.userId(),
                    "user",
                    currentUser.email(),
                    "",
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    true,
                    false,
                    false,
                    false,
                    currentUser.role(),
                    0.0,
                    1,
                    "active",
                    List.of(),
                    false,
                    null,
                    List.of(),
                    0,
                    "email",
                    null,
                    null,
                    null,
                    "user"
            );
        });
        doNothing().when(authTurnstileService).verify(any(), any());

        JwtService jwtService = new JwtService(new JwtProperties("01234567890123456789012345678901", 60, null, 30));
        service = new AuthLifecycleService(
                authFlowUserRepository,
                authRefreshTokenRepository,
                authPublicEmailRepository,
                jwtService,
                mock(UserTotpService.class),
                currentUserService,
                publicSettingsService,
                authTurnstileService,
                new PasswordHasher(),
                mock(UserTotpEmailService.class),
                adminSubscriptionRepository,
                new JsonHelper(new ObjectMapper()),
                mock(NamedParameterJdbcTemplate.class)
        );
    }

    @Test
    void registerBindsAffiliateInviterWhenCodeIsValid() {
        prepareUserCreation(101L, "new@example.test");
        when(authPublicEmailRepository.findAffiliateInviterIdForUpdate(101L)).thenReturn(Optional.empty());
        when(authPublicEmailRepository.findAffiliateUserIdByCodeForUpdate("AFF-88")).thenReturn(Optional.of(88L));

        AuthTokenResponse response = service.register(
                "new@example.test",
                "secret123",
                null,
                "turnstile",
                "127.0.0.1",
                null,
                null,
                " aff-88 "
        );

        assertEquals(101L, response.user().id());
        verify(authPublicEmailRepository).bindAffiliateInviter(101L, 88L);
    }

    @Test
    void registerVerifiedOAuthEmailAccountBindsAffiliateInviter() {
        prepareUserCreation(202L, "oauth@example.test");
        when(authPublicEmailRepository.findAffiliateInviterIdForUpdate(202L)).thenReturn(Optional.empty());
        when(authPublicEmailRepository.findAffiliateUserIdByCodeForUpdate("AFF-GH")).thenReturn(Optional.of(77L));

        AuthTokenResponse response = service.registerVerifiedOAuthEmailAccount(
                "oauth@example.test",
                "secret123",
                null,
                "github",
                "aff-gh"
        );

        assertEquals(202L, response.user().id());
        verify(authPublicEmailRepository).bindAffiliateInviter(202L, 77L);
    }

    @Test
    void registerSyntheticOAuthAccountBindsAffiliateInviter() {
        prepareUserCreation(303L, "linuxdo-connect.invalid");
        when(authPublicEmailRepository.findAffiliateInviterIdForUpdate(303L)).thenReturn(Optional.empty());
        when(authPublicEmailRepository.findAffiliateUserIdByCodeForUpdate("AFF-LD")).thenReturn(Optional.of(66L));

        AuthTokenResponse response = service.registerSyntheticOAuthAccount(
                "tester@linuxdo-connect.invalid",
                "tester",
                null,
                "linuxdo",
                "aff-ld"
        );

        assertEquals(303L, response.user().id());
        verify(authPublicEmailRepository).bindAffiliateInviter(303L, 66L);
    }

    @Test
    void registerSkipsAffiliateBindingWhenDisabled() {
        when(publicSettingsService.getPublicSettings()).thenReturn(publicSettingsDisabledAffiliate());
        prepareUserCreation(404L, "disabled@example.test");

        service.register(
                "disabled@example.test",
                "secret123",
                null,
                "turnstile",
                "127.0.0.1",
                null,
                null,
                "aff-off"
        );

        verify(authPublicEmailRepository, never()).findAffiliateUserIdByCodeForUpdate(any());
        verify(authPublicEmailRepository, never()).bindAffiliateInviter(any(Long.class), any(Long.class));
    }

    private void prepareUserCreation(long userId, String email) {
        when(authPublicEmailRepository.existsActiveUserByEmail(any())).thenReturn(false);
        when(authPublicEmailRepository.createUser(any())).thenReturn(userId);
        doNothing().when(authPublicEmailRepository).ensureEmailIdentity(eq(userId), any(), any());
        doNothing().when(authPublicEmailRepository).ensureUserAffiliate(any(Long.class));
        AuthFlowUserRepository.AuthUserRow user = new AuthFlowUserRepository.AuthUserRow(
                userId,
                email,
                "user",
                "hash",
                "user",
                "active",
                0L,
                false
        );
        when(authFlowUserRepository.findById(userId)).thenReturn(Optional.of(user));
        doNothing().when(authFlowUserRepository).touchSuccessfulLogin(userId);
        doNothing().when(authRefreshTokenRepository).store(any());
    }

    private PublicSettingsResponse publicSettings() {
        return new PublicSettingsResponse(
                true,
                false,
                false,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                "",
                "api-private-router",
                "",
                "",
                "",
                "",
                "",
                "",
                false,
                false,
                "",
                20,
                List.of(20, 50),
                List.<CustomMenuItem>of(),
                List.<CustomEndpoint>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                "",
                false,
                false,
                false,
                false,
                false,
                "test",
                false,
                false,
                0.0,
                "",
                false,
                60,
                false,
                true,
                false,
                false,
                false,
                false,
                false
        );
    }

    private PublicSettingsResponse publicSettingsDisabledAffiliate() {
        return new PublicSettingsResponse(
                true,
                false,
                false,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                "",
                "api-private-router",
                "",
                "",
                "",
                "",
                "",
                "",
                false,
                false,
                "",
                20,
                List.of(20, 50),
                List.<CustomMenuItem>of(),
                List.<CustomEndpoint>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                "",
                false,
                false,
                false,
                false,
                false,
                "test",
                false,
                false,
                0.0,
                "",
                false,
                60,
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }
}
