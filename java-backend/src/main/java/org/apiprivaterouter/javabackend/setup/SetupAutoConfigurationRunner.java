package org.apiprivaterouter.javabackend.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.apiprivaterouter.javabackend.common.config.AutoSetupProperties;

import java.security.SecureRandom;

@Component
public class SetupAutoConfigurationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SetupAutoConfigurationRunner.class);

    private final SetupService setupService;
    private final AutoSetupProperties autoSetupProperties;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    public SetupAutoConfigurationRunner(
            SetupService setupService,
            AutoSetupProperties autoSetupProperties,
            Environment environment
    ) {
        this.setupService = setupService;
        this.autoSetupProperties = autoSetupProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoSetupProperties.enabled()) {
            return;
        }
        if (!setupService.needsSetup()) {
            log.info("Auto setup skipped: system already installed");
            return;
        }
        SetupInstallRequest request = buildRequest();
        log.info("Auto setup enabled, applying setup from environment variables");
        setupService.install(request);
        log.info("Auto setup completed");
    }

    private SetupInstallRequest buildRequest() {
        String adminPassword = env("ADMIN_PASSWORD", null, "");
        if (adminPassword.isBlank()) {
            adminPassword = generateSecret(16);
            log.warn("ADMIN_PASSWORD is blank, generated one-time admin password: {}", adminPassword);
        }
        return new SetupInstallRequest(
                new SetupDatabaseRequest(
                        env("DATABASE_HOST", "database.host", "127.0.0.1"),
                        envInt("DATABASE_PORT", "database.port", 5432),
                        env("DATABASE_USER", "database.user", "api-private-router"),
                        env("DATABASE_PASSWORD", "database.password", ""),
                        env("DATABASE_DBNAME", "database.dbname", "api-private-router"),
                        env("DATABASE_SSLMODE", "database.sslmode", "disable")
                ),
                new SetupRedisRequest(
                        env("REDIS_HOST", "redis.host", "127.0.0.1"),
                        envInt("REDIS_PORT", "redis.port", 6379),
                        env("REDIS_PASSWORD", "redis.password", ""),
                        envInt("REDIS_DB", "redis.db", 0),
                        envBoolean("REDIS_ENABLE_TLS", "redis.enable_tls", false)
                ),
                new SetupAdminRequest(
                        env("ADMIN_EMAIL", null, "admin@api-private-router.local"),
                        adminPassword
                ),
                new SetupServerRequest(
                        env("SERVER_HOST", "server.host", "0.0.0.0"),
                        envInt("SERVER_PORT", "server.port", 8080),
                        env("SERVER_MODE", "server.mode", "release")
                )
        );
    }

    private String env(String primaryEnvKey, String propertyKey, String fallback) {
        String value = trim(environment.getProperty(primaryEnvKey));
        if (value != null) {
            return value;
        }
        if (propertyKey != null) {
            value = trim(environment.getProperty(propertyKey));
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private Integer envInt(String primaryEnvKey, String propertyKey, int fallback) {
        String raw = env(primaryEnvKey, propertyKey, Integer.toString(fallback));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Boolean envBoolean(String primaryEnvKey, String propertyKey, boolean fallback) {
        String raw = env(primaryEnvKey, propertyKey, Boolean.toString(fallback));
        if (raw == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateSecret(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
