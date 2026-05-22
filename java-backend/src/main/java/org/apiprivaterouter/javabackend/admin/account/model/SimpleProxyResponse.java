package org.apiprivaterouter.javabackend.admin.account.model;

public record SimpleProxyResponse(
        long id,
        String name,
        String protocol,
        String host,
        int port,
        String username,
        String status,
        String country_code,
        String created_at,
        String updated_at
) {
}
