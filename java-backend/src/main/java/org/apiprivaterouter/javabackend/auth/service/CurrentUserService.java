package org.apiprivaterouter.javabackend.auth.service;

import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.repository.CurrentUserIdentityRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.usercenter.model.UserProfileResponse;
import org.apiprivaterouter.javabackend.usercenter.service.UserCenterService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CurrentUserService {

    private static final List<String> PROVIDER_ORDER = List.of("email", "linuxdo", "oidc", "wechat", "github", "google");
    private static final String IDENTITY_BIND_START_PATH = "/api/v1/user/auth-identities/bind/start";
    private static final String RUN_MODE_STANDARD = "standard";

    private final UserCenterService userCenterService;
    private final CurrentUserIdentityRepository identityRepository;
    private final JsonHelper jsonHelper;
    private final Environment environment;

    public CurrentUserService(
            UserCenterService userCenterService,
            CurrentUserIdentityRepository identityRepository,
            JsonHelper jsonHelper,
            Environment environment
    ) {
        this.userCenterService = userCenterService;
        this.identityRepository = identityRepository;
        this.jsonHelper = jsonHelper;
        this.environment = environment;
    }

    public CurrentUserResponse getCurrentUser(CurrentUser currentUser) {
        UserProfileResponse profile = userCenterService.getProfile(currentUser);
        Map<String, CurrentUserIdentityRepository.IdentityBindingRow> bindings = indexBindings(currentUser.userId());

        boolean emailBound = bindings.containsKey("email") || hasBindableEmail(profile.email());
        boolean linuxdoBound = bindings.containsKey("linuxdo");
        boolean oidcBound = bindings.containsKey("oidc");
        boolean wechatBound = bindings.containsKey("wechat");

        CurrentUserResponse.ProfileSourceContext avatarSource =
                inferAvatarSource(profile.avatar_url(), bindings);
        CurrentUserResponse.ProfileSourceContext usernameSource =
                inferUsernameSource(profile.username(), bindings);

        Map<String, CurrentUserResponse.ProfileSourceContext> profileSources = new LinkedHashMap<>();
        if (avatarSource != null) {
            profileSources.put("avatar", avatarSource);
        }
        if (usernameSource != null) {
            profileSources.put("username", usernameSource);
            profileSources.put("display_name", usernameSource);
            profileSources.put("nickname", usernameSource);
        }

        Map<String, CurrentUserResponse.AuthBindingStatus> authBindings = new LinkedHashMap<>();
        for (String provider : PROVIDER_ORDER) {
            authBindings.put(provider, buildBindingStatus(provider, bindings.get(provider), emailBound));
        }

        return new CurrentUserResponse(
                profile.id(),
                profile.username(),
                profile.email(),
                profile.avatar_url(),
                avatarSource,
                usernameSource,
                usernameSource,
                usernameSource,
                profileSources.isEmpty() ? null : profileSources,
                authBindings,
                authBindings,
                emailBound,
                linuxdoBound,
                oidcBound,
                wechatBound,
                profile.role(),
                profile.balance(),
                profile.concurrency(),
                profile.status(),
                profile.allowed_groups(),
                profile.balance_notify_enabled(),
                profile.balance_notify_threshold(),
                profile.balance_notify_extra_emails(),
                profile.rpm_limit(),
                profile.signup_source(),
                profile.last_active_at(),
                profile.created_at(),
                profile.updated_at(),
                resolveRunMode()
        );
    }

    private Map<String, CurrentUserIdentityRepository.IdentityBindingRow> indexBindings(long userId) {
        Map<String, CurrentUserIdentityRepository.IdentityBindingRow> bindings = new LinkedHashMap<>();
        for (CurrentUserIdentityRepository.IdentityBindingRow row : identityRepository.listByUserId(userId)) {
            String provider = normalizeProvider(row.provider_type());
            if (provider != null && !bindings.containsKey(provider)) {
                bindings.put(provider, row);
            }
        }
        return bindings;
    }

    private CurrentUserResponse.AuthBindingStatus buildBindingStatus(
            String provider,
            CurrentUserIdentityRepository.IdentityBindingRow row,
            boolean emailBound
    ) {
        boolean bound = row != null || ("email".equals(provider) && emailBound);
        boolean canBind = !"email".equals(provider) && !bound;
        boolean canUnbind = !"email".equals(provider) && bound;
        Map<String, Object> metadata = row == null ? Map.of() : jsonHelper.readObjectMap(row.metadata_json());
        return new CurrentUserResponse.AuthBindingStatus(
                bound,
                bound ? 1 : 0,
                provider,
                row == null ? null : row.provider_key(),
                row == null ? null : row.provider_subject(),
                row == null ? null : row.issuer(),
                null,
                null,
                firstNonBlank(
                        metadata.get("display_name"),
                        metadata.get("username"),
                        metadata.get("name"),
                        metadata.get("nickname")
                ),
                row == null ? null : row.provider_subject(),
                row == null || row.verified_at() == null ? null : row.verified_at().toInstant().toString(),
                "email".equals(provider) ? null : IDENTITY_BIND_START_PATH,
                canBind,
                canUnbind,
                null,
                null,
                metadata.isEmpty() ? null : metadata
        );
    }

    private CurrentUserResponse.ProfileSourceContext inferAvatarSource(
            String avatarUrl,
            Map<String, CurrentUserIdentityRepository.IdentityBindingRow> bindings
    ) {
        String normalized = normalizeText(avatarUrl);
        if (normalized == null) {
            return null;
        }
        for (String provider : List.of("linuxdo", "oidc", "wechat")) {
            Map<String, Object> metadata = metadata(bindings.get(provider));
            if (normalized.equals(normalizeText(metadata.get("avatar_url")))) {
                return buildProfileSource(provider);
            }
        }
        return null;
    }

    private CurrentUserResponse.ProfileSourceContext inferUsernameSource(
            String username,
            Map<String, CurrentUserIdentityRepository.IdentityBindingRow> bindings
    ) {
        String normalized = normalizeText(username);
        if (normalized == null) {
            return null;
        }
        for (String provider : List.of("linuxdo", "oidc", "wechat")) {
            Map<String, Object> metadata = metadata(bindings.get(provider));
            if (normalized.equals(normalizeText(metadata.get("username")))
                    || normalized.equals(normalizeText(metadata.get("display_name")))
                    || normalized.equals(normalizeText(metadata.get("nickname")))) {
                return buildProfileSource(provider);
            }
        }
        return null;
    }

    private Map<String, Object> metadata(CurrentUserIdentityRepository.IdentityBindingRow row) {
        if (row == null) {
            return Map.of();
        }
        return jsonHelper.readObjectMap(row.metadata_json());
    }

    private CurrentUserResponse.ProfileSourceContext buildProfileSource(String provider) {
        return new CurrentUserResponse.ProfileSourceContext(provider, provider, null, null);
    }

    private boolean hasBindableEmail(String email) {
        String normalized = normalizeText(email);
        return normalized != null && !normalized.endsWith(".invalid");
    }

    private String normalizeProvider(String provider) {
        String normalized = normalizeText(provider);
        if (normalized == null) {
            return null;
        }
        return PROVIDER_ORDER.contains(normalized) ? normalized : null;
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String normalized = normalizeText(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String resolveRunMode() {
        String configured = normalizeText(environment.getProperty("run_mode"));
        if (configured == null) {
            configured = normalizeText(environment.getProperty("RUN_MODE"));
        }
        if ("simple".equalsIgnoreCase(configured)) {
            return "simple";
        }
        return RUN_MODE_STANDARD;
    }
}
