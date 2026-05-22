package org.apiprivaterouter.javabackend.payment.model;

import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(
        Double amount,
        @NotBlank String payment_type,
        String openid,
        String wechat_resume_token,
        String return_url,
        String payment_source,
        String order_type,
        Long plan_id,
        Boolean is_mobile
) {
}
