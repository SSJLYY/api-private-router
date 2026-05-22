package org.apiprivaterouter.javabackend.authsession.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.auth.service.AuthLifecycleService;

import java.util.Map;

@Service
public class AuthSessionService {

    private final AuthLifecycleService authLifecycleService;

    public AuthSessionService(
            AuthLifecycleService authLifecycleService
    ) {
        this.authLifecycleService = authLifecycleService;
    }

    @Transactional
    public Map<String, String> revokeAllSessions(long userId) {
        authLifecycleService.revokeAllSessions(userId);
        return Map.of("message", "All sessions have been revoked. Please log in again.");
    }
}
