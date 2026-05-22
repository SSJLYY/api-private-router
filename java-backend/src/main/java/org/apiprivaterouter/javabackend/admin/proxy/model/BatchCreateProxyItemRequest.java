package org.apiprivaterouter.javabackend.admin.proxy.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record BatchCreateProxyItemRequest(
        @NotBlank @Pattern(regexp = "http|https|socks5|socks5h") String protocol,
        @NotBlank String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        String username,
        String password
) {
}
