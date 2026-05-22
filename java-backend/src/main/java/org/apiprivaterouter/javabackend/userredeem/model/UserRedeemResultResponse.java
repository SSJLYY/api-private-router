package org.apiprivaterouter.javabackend.userredeem.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserRedeemResultResponse(
        String message,
        String type,
        double value,
        Double new_balance,
        Integer new_concurrency,
        String group_name,
        Integer validity_days
) {
}
