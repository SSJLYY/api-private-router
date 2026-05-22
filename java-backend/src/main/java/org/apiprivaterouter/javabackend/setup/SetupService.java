package org.apiprivaterouter.javabackend.setup;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.config.DataDirectoryProperties;

import javax.net.SocketFactory;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class SetupService {

    private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.\\-:]+$");
    private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final Set<String> VALID_SSL_MODES = Set.of("disable", "require", "verify-ca", "verify-full");
    private static final String CONFIG_FILE_NAME = "config.yaml";
    private static final String INSTALL_LOCK_FILE_NAME = ".installed";
    private static final int DEFAULT_USER_CONCURRENCY = 5;
    private static final int SIMPLE_MODE_ADMIN_CONCURRENCY = 30;
    private static final int DEFAULT_JWT_EXPIRE_HOUR = 24;
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;
    private static final Pattern MIGRATION_FILE_PATTERN = Pattern.compile("^[0-9]{3}[a-z]?_.*\\.sql$");
    private static final Pattern CONCURRENTLY_PATTERN = Pattern.compile("\\bconcurrently\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TX_CONTROL_PATTERN = Pattern.compile("\\b(begin|commit|rollback|start\\s+transaction)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX_CONCURRENTLY_PATTERN = Pattern.compile("^create\\s+(unique\\s+)?index\\s+concurrently\\s+if\\s+not\\s+exists\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DROP_INDEX_CONCURRENTLY_PATTERN = Pattern.compile("^drop\\s+index\\s+concurrently\\s+if\\s+exists\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> RESERVED_DATABASE_NAMES = Set.of("postgres", "template0", "template1");
    private static final String OPS_SCHEMA_FILENAME = "ops_schema.sql";
    private static final String OPS_SCHEMA_RESOURCE_LOCATION = "classpath:setup/" + OPS_SCHEMA_FILENAME;

    private final DataDirectoryProperties dataDirectoryProperties;
    private final ReentrantLock installLock = new ReentrantLock();
    private final SecureRandom secureRandom = new SecureRandom();
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public SetupService(DataDirectoryProperties dataDirectoryProperties) {
        this.dataDirectoryProperties = dataDirectoryProperties;
    }

    public SetupStatusResponse status() {
        return new SetupStatusResponse(needsSetup(), "welcome");
    }

    public Map<String, Object> testDatabase(SetupDatabaseRequest request) {
        ensureSetupAllowed();
        DatabaseConfig config = validateDatabaseRequest(request);
        try {
            testDatabaseConnection(config);
        } catch (HttpStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new HttpStatusException(400, "Connection failed: " + rootMessage(ex));
        }
        return Map.of("message", "Connection successful");
    }

    public Map<String, Object> testRedis(SetupRedisRequest request) {
        ensureSetupAllowed();
        RedisConfig config = validateRedisRequest(request);
        try {
            testRedisConnection(config);
        } catch (HttpStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new HttpStatusException(400, "Connection failed: " + rootMessage(ex));
        }
        return Map.of("message", "Connection successful");
    }

    public Map<String, Object> install(SetupInstallRequest request) {
        ensureSetupAllowed();
        installLock.lock();
        try {
            ensureSetupAllowed();
            InstallConfig config = validateInstallRequest(request);
            try {
                performInstall(config);
            } catch (HttpStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new HttpStatusException(500, "Installation failed: " + rootMessage(ex));
            }
        } finally {
            installLock.unlock();
        }
        return Map.of(
                "message", "Installation completed successfully. Restart the Java service to apply the new configuration.",
                "restart", true
        );
    }

    public boolean needsSetup() {
        Path baseDir = resolveSetupBaseDir();
        return !Files.exists(baseDir.resolve(CONFIG_FILE_NAME)) && !Files.exists(baseDir.resolve(INSTALL_LOCK_FILE_NAME));
    }

    private void performInstall(InstallConfig config) throws Exception {
        ensureConfigDirectoryWritable();
        testDatabaseConnection(config.database());
        testRedisConnection(config.redis());
        applyMigrations(config.database());
        bootstrapAdminUserIfNeeded(config.database(), config.admin());
        writeConfigFile(config);
        createInstallLock();
    }

    private void ensureSetupAllowed() {
        if (!needsSetup()) {
            throw new HttpStatusException(403, "Setup is not allowed: system is already installed");
        }
    }

    private InstallConfig validateInstallRequest(SetupInstallRequest request) {
        if (request == null) {
            throw new HttpStatusException(400, "Invalid request: request body is required");
        }
        DatabaseConfig database = validateDatabaseRequest(request.database());
        RedisConfig redis = validateRedisRequest(request.redis());
        AdminConfig admin = validateAdminRequest(request.admin());
        ServerConfig server = validateServerRequest(request.server());
        return new InstallConfig(
                database,
                redis,
                admin,
                server,
                new JwtConfig(generateSecret(32), DEFAULT_JWT_EXPIRE_HOUR),
                DEFAULT_TIMEZONE
        );
    }

    private DatabaseConfig validateDatabaseRequest(SetupDatabaseRequest request) {
        if (request == null) {
            throw new HttpStatusException(400, "Invalid request: database config is required");
        }
        String host = trimToNull(request.host());
        Integer port = request.port();
        String user = trimToNull(request.user());
        String password = request.password() == null ? "" : request.password();
        String dbName = trimToNull(request.dbname());
        String sslMode = trimToNull(request.sslmode());

        if (!isValidHostname(host)) {
            throw new HttpStatusException(400, "Invalid hostname format");
        }
        if (!isValidPort(port)) {
            throw new HttpStatusException(400, "Invalid port number");
        }
        if (!isValidUsername(user)) {
            throw new HttpStatusException(400, "Invalid username format");
        }
        if (!isValidDatabaseName(dbName)) {
            throw new HttpStatusException(400, "Invalid database name format");
        }
        if (RESERVED_DATABASE_NAMES.contains(dbName.toLowerCase(Locale.ROOT))) {
            throw new HttpStatusException(400, "Target database name is reserved by PostgreSQL");
        }
        if (sslMode == null) {
            sslMode = "disable";
        }
        if (!VALID_SSL_MODES.contains(sslMode)) {
            throw new HttpStatusException(400, "Invalid SSL mode");
        }
        if (host != null && host.contains(":") && !(host.startsWith("[") && host.endsWith("]"))) {
            throw new HttpStatusException(400, "IPv6 database host must be enclosed in brackets");
        }
        return new DatabaseConfig(host, port, user, password, dbName, sslMode);
    }

    private RedisConfig validateRedisRequest(SetupRedisRequest request) {
        if (request == null) {
            throw new HttpStatusException(400, "Invalid request: redis config is required");
        }
        String host = trimToNull(request.host());
        Integer port = request.port();
        String password = request.password() == null ? "" : request.password();
        int db = request.db() == null ? 0 : request.db();
        boolean enableTls = Boolean.TRUE.equals(request.enable_tls());

        if (!isValidHostname(host)) {
            throw new HttpStatusException(400, "Invalid hostname format");
        }
        if (!isValidPort(port)) {
            throw new HttpStatusException(400, "Invalid port number");
        }
        if (db < 0 || db > 15) {
            throw new HttpStatusException(400, "Invalid Redis database number (0-15)");
        }
        return new RedisConfig(host, port, password, db, enableTls);
    }

    private AdminConfig validateAdminRequest(SetupAdminRequest request) {
        if (request == null) {
            throw new HttpStatusException(400, "Invalid request: admin config is required");
        }
        String email = trimToNull(request.email());
        String password = request.password() == null ? "" : request.password();
        if (!isValidEmail(email)) {
            throw new HttpStatusException(400, "Invalid admin email format");
        }
        if (password.length() < 8) {
            throw new HttpStatusException(400, "password must be at least 8 characters");
        }
        if (password.length() > 128) {
            throw new HttpStatusException(400, "password must be at most 128 characters");
        }
        return new AdminConfig(email, password);
    }

    private ServerConfig validateServerRequest(SetupServerRequest request) {
        String host = request == null ? null : trimToNull(request.host());
        Integer port = request == null ? null : request.port();
        String mode = request == null ? null : trimToNull(request.mode());

        if (host == null) {
            host = "0.0.0.0";
        }
        if (port == null || port == 0) {
            port = 8080;
        }
        if (!isValidPort(port)) {
            throw new HttpStatusException(400, "Invalid server port");
        }
        if (mode == null) {
            mode = "release";
        }
        if (!"release".equals(mode) && !"debug".equals(mode)) {
            throw new HttpStatusException(400, "Invalid server mode (must be 'release' or 'debug')");
        }
        return new ServerConfig(host, port, mode);
    }

    private void testDatabaseConnection(DatabaseConfig config) throws SQLException {
        try {
            try (Connection ignored = openConnection(config, config.dbName())) {
                return;
            }
        } catch (SQLException ex) {
            if (!isMissingDatabaseError(ex)) {
                throw ex;
            }
        }

        try (Connection maintenance = openConnection(config, "postgres")) {
            if (!databaseExists(maintenance, config.dbName())) {
                try (Statement statement = maintenance.createStatement()) {
                    statement.execute("CREATE DATABASE " + quoteIdentifier(config.dbName()));
                }
            }
        }

        try (Connection ignored = openConnection(config, config.dbName())) {
            // Successful reconnect to target database.
        }
    }

    private void testRedisConnection(RedisConfig config) throws IOException {
        try (
                Socket socket = openRedisSocket(config);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())
        ) {
            if (!config.password().isBlank()) {
                writeRedisCommand(output, "AUTH", config.password());
                expectRedisSimpleString(input, "OK");
            }
            if (config.db() != 0) {
                writeRedisCommand(output, "SELECT", Integer.toString(config.db()));
                expectRedisSimpleString(input, "OK");
            }
            writeRedisCommand(output, "PING");
            String pong = readRedisReply(input);
            if (!"PONG".equalsIgnoreCase(pong) && !"OK".equalsIgnoreCase(pong)) {
                throw new IOException("unexpected Redis PING response: " + pong);
            }
        }
    }

    private void applyMigrations(DatabaseConfig config) throws Exception {
        List<Resource> migrations = loadMigrations();
        if (migrations.isEmpty()) {
            throw new IllegalStateException("no SQL migrations were packaged into the Java backend");
        }
        try (Connection connection = openConnection(config, config.dbName())) {
            ensureInstallTargetIsSafe(connection);
            ensureSchemaMigrationsTable(connection);
            if (findAppliedChecksum(connection, OPS_SCHEMA_FILENAME) == null) {
                ensureOpsSchema(connection);
            }
            for (Resource migration : migrations) {
                String filename = migration.getFilename();
                if (filename == null) {
                    continue;
                }
                byte[] content = migration.getInputStream().readAllBytes();
                validateMigrationContent(filename, new String(content, StandardCharsets.UTF_8));
                String checksum = sha256Hex(content);
                String existingChecksum = findAppliedChecksum(connection, filename);
                if (existingChecksum != null) {
                    if (!existingChecksum.equals(checksum)) {
                        throw new IllegalStateException("migration " + filename + " checksum mismatch");
                    }
                    continue;
                }
                runMigration(connection, new String(content, StandardCharsets.UTF_8), filename, checksum);
            }
        }
    }

    private void bootstrapAdminUserIfNeeded(DatabaseConfig database, AdminConfig admin) throws SQLException {
        try (Connection connection = openConnection(database, database.dbName())) {
            long totalUsers = queryCount(connection, "select count(1) from users");
            long adminUsers = queryCount(connection, "select count(1) from users where role = ?", "admin");
            if (adminUsers > 0 || totalUsers > 0) {
                return;
            }

            Timestamp now = Timestamp.from(Instant.now());
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into users (
                        email, password_hash, role, balance, concurrency, status, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, admin.email());
                statement.setString(2, BCrypt.hashpw(admin.password(), BCrypt.gensalt()));
                statement.setString(3, "admin");
                statement.setBigDecimal(4, BigDecimal.ZERO);
                statement.setInt(5, defaultAdminConcurrency());
                statement.setString(6, "active");
                statement.setTimestamp(7, now);
                statement.setTimestamp(8, now);
                statement.executeUpdate();
            }
        }
    }

    private void writeConfigFile(InstallConfig config) throws IOException {
        Path configPath = resolveSetupBaseDir().resolve(CONFIG_FILE_NAME);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, renderConfigYaml(config), StandardCharsets.UTF_8);
    }

    private void createInstallLock() throws IOException {
        Path lockPath = resolveSetupBaseDir().resolve(INSTALL_LOCK_FILE_NAME);
        Files.createDirectories(lockPath.getParent());
        String content = "installed_at=" + DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)) + System.lineSeparator();
        Files.writeString(lockPath, content, StandardCharsets.UTF_8);
    }

    private Connection openConnection(DatabaseConfig config, String dbName) throws SQLException {
        DriverManager.setLoginTimeout(5);
        Properties properties = new Properties();
        properties.setProperty("user", config.user());
        properties.setProperty("password", config.password());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "5");
        return DriverManager.getConnection(buildJdbcUrl(config, dbName), properties);
    }

    private String buildJdbcUrl(DatabaseConfig config, String dbName) {
        return "jdbc:postgresql://"
                + config.host()
                + ":"
                + config.port()
                + "/"
                + dbName
                + "?sslmode="
                + config.sslMode();
    }

    private boolean databaseExists(Connection connection, String dbName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select exists(select 1 from pg_database where datname = ?)"
        )) {
            statement.setString(1, dbName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private boolean isMissingDatabaseError(SQLException exception) {
        if ("3D000".equalsIgnoreCase(exception.getSQLState())) {
            return true;
        }
        String message = rootMessage(exception).toLowerCase(Locale.ROOT);
        return message.contains("does not exist") || message.contains("unknown database");
    }

    private Socket openRedisSocket(RedisConfig config) throws IOException {
        Socket socket;
        if (config.enableTls()) {
            SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(parameters);
            socket = sslSocket;
        } else {
            SocketFactory socketFactory = SocketFactory.getDefault();
            socket = socketFactory.createSocket();
        }
        socket.connect(new InetSocketAddress(config.host(), config.port()), SOCKET_TIMEOUT_MILLIS);
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return socket;
    }

    private void writeRedisCommand(BufferedOutputStream output, String... parts) throws IOException {
        output.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private void expectRedisSimpleString(BufferedInputStream input, String expected) throws IOException {
        String value = readRedisReply(input);
        if (!expected.equalsIgnoreCase(value)) {
            throw new IOException("unexpected Redis response: " + value);
        }
    }

    private String readRedisReply(BufferedInputStream input) throws IOException {
        int prefix = input.read();
        if (prefix == -1) {
            throw new IOException("unexpected end of Redis stream");
        }
        return switch (prefix) {
            case '+' -> readRedisLine(input);
            case '-' -> throw new IOException(readRedisLine(input));
            case ':' -> readRedisLine(input);
            case '$' -> {
                int length = Integer.parseInt(readRedisLine(input));
                if (length < 0) {
                    yield "";
                }
                byte[] bytes = input.readNBytes(length);
                discardRedisCrlf(input);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            default -> throw new IOException("unsupported Redis reply prefix: " + (char) prefix);
        };
    }

    private String readRedisLine(BufferedInputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                builder.setLength(builder.length() - 1);
                break;
            }
            builder.append((char) current);
            previous = current;
        }
        return builder.toString();
    }

    private void discardRedisCrlf(BufferedInputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("invalid Redis bulk string terminator");
        }
    }

    private List<Resource> loadMigrations() throws IOException {
        Resource[] resources = resourceResolver.getResources("classpath*:migrations/*.sql");
        List<Resource> ordered = new ArrayList<>(Arrays.asList(resources));
        ordered.sort(Comparator.comparing(resource -> resource.getFilename() == null ? "" : resource.getFilename()));
        for (Resource resource : ordered) {
            String filename = resource.getFilename();
            if (filename == null || !MIGRATION_FILE_PATTERN.matcher(filename).matches()) {
                throw new IllegalStateException("unexpected migration filename: " + filename);
            }
        }
        return ordered;
    }

    private void ensureSchemaMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists schema_migrations (
                        filename text primary key,
                        checksum text not null,
                        applied_at timestamptz not null default now()
                    )
                    """);
        }
    }

    private String findAppliedChecksum(Connection connection, String filename) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select checksum from schema_migrations where filename = ?"
        )) {
            statement.setString(1, filename);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getString(1);
            }
        }
    }

    private void ensureOpsSchema(Connection connection) throws Exception {
        Resource resource = resourceResolver.getResource(OPS_SCHEMA_RESOURCE_LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException("ops schema resource not found: " + OPS_SCHEMA_RESOURCE_LOCATION);
        }
        byte[] content = resource.getInputStream().readAllBytes();
        String sql = new String(content, StandardCharsets.UTF_8);
        validateMigrationContent(OPS_SCHEMA_FILENAME, sql);
        String checksum = sha256Hex(content);
        String existingChecksum = findAppliedChecksum(connection, OPS_SCHEMA_FILENAME);
        if (existingChecksum != null) {
            if (!existingChecksum.equals(checksum)) {
                throw new IllegalStateException("migration " + OPS_SCHEMA_FILENAME + " checksum mismatch");
            }
            return;
        }
        runMigration(connection, sql, OPS_SCHEMA_FILENAME, checksum);
    }

    private void runMigration(Connection connection, String content, String filename, String checksum) throws Exception {
        boolean nonTransactional = filename.endsWith("_notx.sql");
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(nonTransactional);
            if (nonTransactional) {
                executeNonTransactionalMigration(connection, content);
            } else {
                executeSqlBatch(connection, content);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into schema_migrations (filename, checksum) values (?, ?)"
            )) {
                statement.setString(1, filename);
                statement.setString(2, checksum);
                statement.executeUpdate();
            }
            if (!nonTransactional) {
                connection.commit();
            }
        } catch (Exception ex) {
            if (!nonTransactional) {
                connection.rollback();
            }
            throw ex;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void executeNonTransactionalMigration(Connection connection, String content) throws SQLException {
        for (String statement : splitMigrationStatements(content)) {
            String executableSql = statement == null ? "" : statement.trim();
            if (stripSqlComments(executableSql).trim().isEmpty()) {
                continue;
            }
            executeSqlBatch(connection, executableSql);
        }
    }

    private void executeSqlBatch(Connection connection, String sql) throws SQLException {
        String executableSql = sql == null ? "" : sql.trim();
        if (executableSql.isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(executableSql);
        }
    }

    private void validateMigrationContent(String filename, String content) {
        if (filename == null) {
            throw new IllegalStateException("migration filename is missing");
        }
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            throw new IllegalStateException("migration " + filename + " is empty");
        }
        boolean nonTransactional = filename.endsWith("_notx.sql");
        String contentWithoutComments = stripSqlComments(normalized);
        boolean hasConcurrently = CONCURRENTLY_PATTERN.matcher(normalized).find();
        boolean hasTxControl = TX_CONTROL_PATTERN.matcher(stripDollarQuotedBlocks(contentWithoutComments)).find();
        if (hasTxControl) {
            throw new IllegalStateException("migration " + filename + " must not contain transaction control statements");
        }
        if (!nonTransactional && hasConcurrently) {
            throw new IllegalStateException("migration " + filename + " uses CONCURRENTLY but is not marked _notx.sql");
        }
        if (!nonTransactional) {
            return;
        }
        for (String statement : splitMigrationStatements(normalized)) {
            String sql = stripSqlComments(statement).trim();
            if (sql.isEmpty()) {
                continue;
            }
            if (!CREATE_INDEX_CONCURRENTLY_PATTERN.matcher(sql).matches()
                    && !DROP_INDEX_CONCURRENTLY_PATTERN.matcher(sql).matches()) {
                throw new IllegalStateException("non-transactional migration " + filename + " may only contain CREATE/DROP INDEX CONCURRENTLY IF EXISTS/IF NOT EXISTS statements");
            }
        }
    }

    private List<String> splitMigrationStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String dollarTag = null;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (dollarTag == null && !inSingleQuote && !inDoubleQuote && ch == '$') {
                int end = findDollarTagEnd(content, i);
                if (end > i) {
                    dollarTag = content.substring(i, end + 1);
                    current.append(dollarTag);
                    i = end;
                    continue;
                }
            }
            if (dollarTag != null) {
                current.append(ch);
                if (content.startsWith(dollarTag, i)) {
                    if (dollarTag.length() > 1) {
                        current.append(content, i + 1, i + dollarTag.length());
                    }
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                boolean escaped = i + 1 < content.length() && content.charAt(i + 1) == '\'';
                current.append(ch);
                if (escaped) {
                    current.append('\'');
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(ch);
                continue;
            }
            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private int findDollarTagEnd(String content, int start) {
        for (int i = start + 1; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '$') {
                return i;
            }
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                return -1;
            }
        }
        return -1;
    }

    private String stripSqlComments(String content) {
        StringBuilder builder = new StringBuilder(content.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (!inSingleQuote && !inDoubleQuote && ch == '-' && next == '-') {
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
                if (i < content.length()) {
                    builder.append('\n');
                }
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && ch == '/' && next == '*') {
                i += 2;
                while (i < content.length() - 1 && !(content.charAt(i) == '*' && content.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                boolean escaped = i + 1 < content.length() && content.charAt(i + 1) == '\'';
                builder.append(ch);
                if (escaped) {
                    builder.append('\'');
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private String stripDollarQuotedBlocks(String content) {
        StringBuilder builder = new StringBuilder(content.length());
        String dollarTag = null;
        for (int i = 0; i < content.length(); i++) {
            if (dollarTag == null && content.charAt(i) == '$') {
                int end = findDollarTagEnd(content, i);
                if (end > i) {
                    dollarTag = content.substring(i, end + 1);
                    i = end;
                    continue;
                }
            }
            if (dollarTag != null) {
                if (content.startsWith(dollarTag, i)) {
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            builder.append(content.charAt(i));
        }
        return builder.toString();
    }

    private String quoteIdentifier(String value) {
        String normalized = value == null ? "" : value.trim();
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private long queryCount(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void ensureInstallTargetIsSafe(Connection connection) throws SQLException {
        if (schemaMigrationsTableExists(connection)) {
            return;
        }
        int userTableCount = countUserTables(connection);
        if (userTableCount > 0) {
            throw new IllegalStateException("target database is not empty and does not look like a api-private-router database");
        }
    }

    private boolean schemaMigrationsTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select exists(
                    select 1
                    from information_schema.tables
                    where table_schema = 'public'
                      and table_name = 'schema_migrations'
                )
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private int countUserTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(1)
                from information_schema.tables
                where table_schema = 'public'
                  and table_type = 'BASE TABLE'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void ensureConfigDirectoryWritable() throws IOException {
        Path baseDir = resolveSetupBaseDir();
        Files.createDirectories(baseDir);
        if (!Files.isWritable(baseDir)) {
            throw new IOException("setup data directory is not writable: " + baseDir);
        }
    }

    private String renderConfigYaml(InstallConfig config) {
        StringBuilder builder = new StringBuilder();
        builder.append("server:\n");
        appendString(builder, 1, "host", config.server().host());
        appendNumber(builder, 1, "port", config.server().port());
        appendString(builder, 1, "mode", config.server().mode());

        builder.append("database:\n");
        appendString(builder, 1, "host", config.database().host());
        appendNumber(builder, 1, "port", config.database().port());
        appendString(builder, 1, "user", config.database().user());
        appendString(builder, 1, "password", config.database().password());
        appendString(builder, 1, "dbname", config.database().dbName());
        appendString(builder, 1, "sslmode", config.database().sslMode());

        builder.append("redis:\n");
        appendString(builder, 1, "host", config.redis().host());
        appendNumber(builder, 1, "port", config.redis().port());
        appendString(builder, 1, "password", config.redis().password());
        appendNumber(builder, 1, "db", config.redis().db());
        appendBoolean(builder, 1, "enable_tls", config.redis().enableTls());

        builder.append("jwt:\n");
        appendString(builder, 1, "secret", config.jwt().secret());
        appendNumber(builder, 1, "expire_hour", config.jwt().expireHour());

        builder.append("default:\n");
        appendNumber(builder, 1, "user_concurrency", DEFAULT_USER_CONCURRENCY);
        appendNumber(builder, 1, "user_balance", 0);
        appendString(builder, 1, "api_key_prefix", "sk-");
        appendNumber(builder, 1, "rate_multiplier", 1.0);

        builder.append("rate_limit:\n");
        appendNumber(builder, 1, "requests_per_minute", 60);
        appendNumber(builder, 1, "burst_size", 10);

        appendString(builder, 0, "timezone", config.timezone());
        return builder.toString();
    }

    private void appendString(StringBuilder builder, int indent, String key, String value) {
        builder.append("  ".repeat(Math.max(0, indent)))
                .append(key)
                .append(": '")
                .append(escapeYaml(value == null ? "" : value))
                .append("'\n");
    }

    private void appendNumber(StringBuilder builder, int indent, String key, Object value) {
        builder.append("  ".repeat(Math.max(0, indent)))
                .append(key)
                .append(": ")
                .append(value)
                .append('\n');
    }

    private void appendBoolean(StringBuilder builder, int indent, String key, boolean value) {
        appendNumber(builder, indent, key, value);
    }

    private String escapeYaml(String value) {
        return value.replace("'", "''");
    }

    private Path resolveSetupBaseDir() {
        String dataDirEnv = trimToNull(System.getenv("DATA_DIR"));
        if (dataDirEnv != null) {
            return normalizePath(dataDirEnv);
        }
        Path appData = Path.of("/app/data");
        if (Files.isDirectory(appData) && Files.isWritable(appData)) {
            return appData.toAbsolutePath().normalize();
        }
        if (dataDirectoryProperties.dir() != null && !dataDirectoryProperties.dir().isBlank()) {
            return normalizePath(dataDirectoryProperties.dir());
        }
        return workingDir();
    }

    private Path normalizePath(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    private Path workingDir() {
        return Path.of("").toAbsolutePath().normalize();
    }

    private int defaultAdminConcurrency() {
        return "simple".equalsIgnoreCase(trimToNull(System.getenv("RUN_MODE")))
                ? SIMPLE_MODE_ADMIN_CONCURRENCY
                : DEFAULT_USER_CONCURRENCY;
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

    private String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isValidHostname(String host) {
        return host != null && host.length() <= 253 && HOST_PATTERN.matcher(host).matches();
    }

    private boolean isValidPort(Integer port) {
        return port != null && port > 0 && port <= 65_535;
    }

    private boolean isValidUsername(String username) {
        return username != null && username.length() <= 63 && USERNAME_PATTERN.matcher(username).matches();
    }

    private boolean isValidDatabaseName(String dbName) {
        return dbName != null && dbName.length() <= 63 && DATABASE_NAME_PATTERN.matcher(dbName).matches();
    }

    private boolean isValidEmail(String email) {
        if (email == null || email.length() > 254) {
            return false;
        }
        try {
            InternetAddress address = new InternetAddress(email);
            address.validate();
            return true;
        } catch (AddressException ex) {
            return false;
        }
    }

    private record DatabaseConfig(
            String host,
            int port,
            String user,
            String password,
            String dbName,
            String sslMode
    ) {
    }

    private record RedisConfig(
            String host,
            int port,
            String password,
            int db,
            boolean enableTls
    ) {
    }

    private record AdminConfig(
            String email,
            String password
    ) {
    }

    private record ServerConfig(
            String host,
            int port,
            String mode
    ) {
    }

    private record JwtConfig(
            String secret,
            int expireHour
    ) {
    }

    private record InstallConfig(
            DatabaseConfig database,
            RedisConfig redis,
            AdminConfig admin,
            ServerConfig server,
            JwtConfig jwt,
            String timezone
    ) {
    }
}
