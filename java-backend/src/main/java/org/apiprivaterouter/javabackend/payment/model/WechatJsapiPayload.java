package org.apiprivaterouter.javabackend.payment.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WechatJsapiPayload(
        String appId,
        String timeStamp,
        String nonceStr,
        @JsonProperty("package")
        String packageValue,
        String signType,
        String paySign
) {
}
