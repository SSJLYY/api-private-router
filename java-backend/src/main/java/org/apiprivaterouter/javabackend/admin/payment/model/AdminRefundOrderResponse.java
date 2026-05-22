package org.apiprivaterouter.javabackend.admin.payment.model;

import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;

public record AdminRefundOrderResponse(
        boolean success,
        String warning,
        boolean require_force,
        double balance_deducted,
        int subscription_days_deducted,
        PaymentOrderResponse order
) {
    public static AdminRefundOrderResponse success(double balanceDeducted, int subscriptionDaysDeducted, PaymentOrderResponse order) {
        return new AdminRefundOrderResponse(true, null, false, balanceDeducted, subscriptionDaysDeducted, order);
    }

    public static AdminRefundOrderResponse warning(String warning, boolean requireForce, PaymentOrderResponse order) {
        return new AdminRefundOrderResponse(false, warning, requireForce, 0.0d, 0, order);
    }
}
