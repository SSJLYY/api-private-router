package org.apiprivaterouter.javabackend.payment.model;

import java.time.OffsetDateTime;
import java.util.Map;

public record PaymentWebhookOrder(
        long id,
        long userId,
        double amount,
        double payAmount,
        double feeRate,
        String paymentType,
        String outTradeNo,
        String status,
        String orderType,
        Long planId,
        Long subscriptionGroupId,
        Integer subscriptionDays,
        String providerInstanceId,
        String providerKey,
        String rechargeCode,
        String paymentTradeNo,
        String providerSnapshotRaw,
        Map<String, Object> providerSnapshot,
        OffsetDateTime updatedAt,
        OffsetDateTime paidAt,
        OffsetDateTime completedAt
) {
}
