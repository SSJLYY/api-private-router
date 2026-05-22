package org.apiprivaterouter.javabackend.admin.account.model;

public record AdminDataProxy(
        String proxy_key,
        String name,
        String protocol,
        String host,
        int port,
        String username,
        String password,
        String status
) {
}
