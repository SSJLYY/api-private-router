package org.apiprivaterouter.javabackend.admin.proxy.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchCreateProxiesRequest(
        @NotEmpty List<@Valid BatchCreateProxyItemRequest> proxies
) {
}
