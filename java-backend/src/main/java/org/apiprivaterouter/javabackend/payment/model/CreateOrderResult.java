package org.apiprivaterouter.javabackend.payment.model;

public record CreateOrderResult(
        long order_id,
        double amount,
        String pay_url,
        String qr_code,
        String client_secret,
        double pay_amount,
        double fee_rate,
        String expires_at,
        String result_type,
        String payment_type,
        String out_trade_no,
        String payment_mode,
        String resume_token,
        WechatOAuthInfo oauth,
        WechatJsapiPayload jsapi,
        WechatJsapiPayload jsapi_payload
) {
}
