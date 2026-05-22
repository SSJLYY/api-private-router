package org.apiprivaterouter.javabackend.admin.backups.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
public class PostgresCommandSupport {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresCommandSupport(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password
    ) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public DbConnectionInfo connectionInfo() {
        String raw = jdbcUrl == null ? "" : jdbcUrl.trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("unsupported datasource url for backups");
        }
        try {
            URI uri = new URI(raw.substring(5));
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String database = path == null ? "" : path.replaceFirst("^/", "");
            if (host == null || host.isBlank() || database.isBlank()) {
                throw new IllegalArgumentException("invalid datasource url for backups");
            }
            return new DbConnectionInfo(host, port, database, username, password);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("invalid datasource url for backups", ex);
        }
    }

    public record DbConnectionInfo(
            String host,
            int port,
            String database,
            String username,
            String password
    ) {
    }
}
