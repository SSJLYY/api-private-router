package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchCreateAccountsRequest(
        @NotEmpty List<@Valid CreateAccountRequest> accounts
) {
}
