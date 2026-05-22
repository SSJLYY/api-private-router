package org.apiprivaterouter.javabackend.admin.redeem.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateAndRedeemCodeResponse(
        @JsonProperty("redeem_code")
        AdminRedeemCodeResponse redeemCode
) {
}
