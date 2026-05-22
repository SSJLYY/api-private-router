package org.apiprivaterouter.javabackend.payment.model;

public record PaymentOrderResponse(
        long id,
        long user_id,
        double amount,
        double pay_amount,
        double fee_rate,
        String payment_type,
        String out_trade_no,
        String status,
        String order_type,
        String created_at,
        String expires_at,
        String paid_at,
        String completed_at,
        double refund_amount,
        String refund_reason,
        String refund_requested_at,
        String refund_requested_by,
        String refund_request_reason,
        Long plan_id,
        String provider_instance_id,
        String provider_key,
        String payment_trade_no,
        String pay_url,
        String qr_code
) {
}
