package org.apiprivaterouter.javabackend.setup;

record SetupStatusResponse(
        boolean needs_setup,
        String step
) {
}

record SetupDatabaseRequest(
        String host,
        Integer port,
        String user,
        String password,
        String dbname,
        String sslmode
) {
}

record SetupRedisRequest(
        String host,
        Integer port,
        String password,
        Integer db,
        Boolean enable_tls
) {
}

record SetupAdminRequest(
        String email,
        String password
) {
}

record SetupServerRequest(
        String host,
        Integer port,
        String mode
) {
}

record SetupInstallRequest(
        SetupDatabaseRequest database,
        SetupRedisRequest redis,
        SetupAdminRequest admin,
        SetupServerRequest server
) {
}
