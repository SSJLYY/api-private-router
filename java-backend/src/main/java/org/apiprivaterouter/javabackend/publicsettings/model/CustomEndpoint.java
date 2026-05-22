package org.apiprivaterouter.javabackend.publicsettings.model;

public record CustomEndpoint(
        String name,
        String endpoint,
        String description
) {
}
