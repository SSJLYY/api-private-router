package org.apiprivaterouter.javabackend.admin.redeem.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateAndRedeemCodeRequest(
        @JsonProperty("code")
        @NotBlank
        @Size(min = 3, max = 128)
        String code,
        @JsonProperty("type")
        String type,
        @JsonProperty("value")
        @NotNull
        Double value,
        @JsonProperty("user_id")
        @NotNull
        @Positive
        Long userId,
        @JsonProperty("group_id")
        Long groupId,
        @JsonProperty("validity_days")
        Integer validityDays,
        @JsonProperty("notes")
        String notes
) {
}
