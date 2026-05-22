package org.apiprivaterouter.javabackend.admin.redeem.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchDeleteRedeemCodesRequest(
        @NotEmpty List<Long> ids
) {
}
