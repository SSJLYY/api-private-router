package org.apiprivaterouter.javabackend.payment.model;

public record WechatOAuthInfo(
        String authorize_url,
        String appid,
        String openid,
        String scope,
        String state,
        String redirect_url
) {
}
