package org.apiprivaterouter.javabackend.payment.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlipayPaymentClientTest {

    @Test
    void createOrderUsesPrecreateQrForDesktop() {
        RecordingAlipayClient alipayClient = new RecordingAlipayClient();
        AlipayTradePrecreateResponse precreate = new AlipayTradePrecreateResponse();
        precreate.setCode("10000");
        precreate.setQrCode("https://qr.alipay.example.com/precreate");
        alipayClient.precreateResponse = precreate;

        AlipayPaymentClient client = new AlipayPaymentClient(config -> alipayClient);
        AlipayPaymentClient.AlipayCreateOrderResult result = client.createOrder(
                provider(),
                order("JAVA-ORDER-ALIPAY-1"),
                new CreateOrderRequest(88.0, "alipay", null, null, "https://app.example.com/payment/result", null, "balance", null, false),
                "https://api.example.com/api/v1/payment/webhook/alipay",
                "https://app.example.com/payment/result?order_id=1",
                "api-private-router 88 CNY"
        );

        assertEquals("https://qr.alipay.example.com/precreate", result.qrCode());
        assertEquals("", result.payUrl());
        assertEquals("qrcode", result.paymentMode());
        assertTrue(alipayClient.lastPrecreate.getNotifyUrl().contains("/api/v1/payment/webhook/alipay"));
        assertEquals("FACE_TO_FACE_PAYMENT", ((AlipayTradePrecreateModel) alipayClient.lastPrecreate.getBizModel()).getProductCode());
    }

    @Test
    void createOrderFallsBackToPagePayWhenPrecreateFails() {
        RecordingAlipayClient alipayClient = new RecordingAlipayClient();
        AlipayTradePrecreateResponse precreate = new AlipayTradePrecreateResponse();
        precreate.setCode("40004");
        precreate.setSubCode("ACQ.INVALID_PARAMETER");
        precreate.setSubMsg("merchant not opened");
        alipayClient.precreateResponse = precreate;

        AlipayTradePagePayResponse pagePay = new AlipayTradePagePayResponse();
        pagePay.setBody("https://openapi.alipay.com/gateway.do?page-pay");
        alipayClient.pagePayResponse = pagePay;

        AlipayPaymentClient client = new AlipayPaymentClient(config -> alipayClient);
        AlipayPaymentClient.AlipayCreateOrderResult result = client.createOrder(
                provider(),
                order("JAVA-ORDER-ALIPAY-2"),
                new CreateOrderRequest(66.8, "alipay", null, null, "https://app.example.com/payment/result", null, "balance", null, false),
                "https://api.example.com/api/v1/payment/webhook/alipay",
                "https://app.example.com/payment/result?order_id=2",
                "api-private-router 66.8 CNY"
        );

        assertEquals("https://openapi.alipay.com/gateway.do?page-pay", result.payUrl());
        assertEquals("https://openapi.alipay.com/gateway.do?page-pay", result.qrCode());
        assertEquals("qrcode", result.paymentMode());
        assertEquals("FAST_INSTANT_TRADE_PAY", ((AlipayTradePagePayModel) alipayClient.lastPagePay.getBizModel()).getProductCode());
    }

    @Test
    void createOrderUsesWapPayForMobile() {
        RecordingAlipayClient alipayClient = new RecordingAlipayClient();
        AlipayTradeWapPayResponse wapPay = new AlipayTradeWapPayResponse();
        wapPay.setBody("https://openapi.alipay.com/gateway.do?wap-pay");
        alipayClient.wapPayResponse = wapPay;

        AlipayPaymentClient client = new AlipayPaymentClient(config -> alipayClient);
        AlipayPaymentClient.AlipayCreateOrderResult result = client.createOrder(
                provider(),
                order("JAVA-ORDER-ALIPAY-3"),
                new CreateOrderRequest(18.0, "alipay", null, null, "https://app.example.com/payment/result", null, "balance", null, true),
                "https://api.example.com/api/v1/payment/webhook/alipay",
                "https://app.example.com/payment/result?order_id=3",
                "api-private-router 18 CNY"
        );

        assertEquals("https://openapi.alipay.com/gateway.do?wap-pay", result.payUrl());
        assertNull(result.qrCode());
        assertEquals("redirect", result.paymentMode());
        assertEquals("QUICK_WAP_WAY", ((AlipayTradeWapPayModel) alipayClient.lastWapPay.getBizModel()).getProductCode());
    }

    @Test
    void queryOrderMapsPaidStatusAndAmount() {
        RecordingAlipayClient alipayClient = new RecordingAlipayClient();
        AlipayTradeQueryResponse query = new AlipayTradeQueryResponse();
        query.setCode("10000");
        query.setTradeNo("202605210001");
        query.setTradeStatus("TRADE_SUCCESS");
        query.setTotalAmount("88.00");
        alipayClient.queryResponse = query;

        AlipayPaymentClient client = new AlipayPaymentClient(config -> alipayClient);
        AlipayPaymentClient.AlipayQueryOrderResult result = client.queryOrder(provider(), order("JAVA-ORDER-ALIPAY-4"));

        assertEquals("202605210001", result.tradeNo());
        assertEquals("paid", result.status());
        assertEquals(88.0, result.amount());
    }

    @Test
    void refundMapsFundChangeToSuccess() {
        RecordingAlipayClient alipayClient = new RecordingAlipayClient();
        AlipayTradeRefundResponse refund = new AlipayTradeRefundResponse();
        refund.setCode("10000");
        refund.setTradeNo("202605210009");
        refund.setFundChange("Y");
        alipayClient.refundResponse = refund;

        AlipayPaymentClient client = new AlipayPaymentClient(config -> alipayClient);
        AlipayPaymentClient.AlipayRefundResult result = client.refund(provider(), order("JAVA-ORDER-ALIPAY-5"), 66.8, "requested");

        assertEquals("202605210009", result.refundId());
        assertEquals("success", result.status());
        assertEquals("66.80", ((AlipayTradeRefundModel) alipayClient.lastRefund.getBizModel()).getRefundAmount());
    }

    private ProviderInstanceResponse provider() {
        return new ProviderInstanceResponse(
                9L,
                "alipay",
                "Alipay",
                Map.of(
                        "appId", "2021001234567890",
                        "privateKey", "private-key",
                        "publicKey", "public-key",
                        "notifyUrl", "https://api.example.com/api/v1/payment/webhook/alipay",
                        "returnUrl", "https://app.example.com/payment/result"
                ),
                List.of("alipay"),
                true,
                "redirect",
                true,
                true,
                "",
                0
        );
    }

    private PaymentOrderResponse order(String outTradeNo) {
        return new PaymentOrderResponse(
                1L,
                10L,
                88.0,
                88.0,
                0.0,
                "alipay",
                outTradeNo,
                "PENDING",
                "balance",
                "2026-05-21T00:00:00Z",
                "2026-05-21T00:30:00Z",
                null,
                null,
                0.0,
                null,
                null,
                null,
                null,
                null,
                "9",
                "alipay",
                "",
                null,
                null
        );
    }

    private static final class RecordingAlipayClient implements AlipayClient {
        private AlipayTradePrecreateResponse precreateResponse = new AlipayTradePrecreateResponse();
        private AlipayTradePagePayResponse pagePayResponse = new AlipayTradePagePayResponse();
        private AlipayTradeWapPayResponse wapPayResponse = new AlipayTradeWapPayResponse();
        private AlipayTradeQueryResponse queryResponse = new AlipayTradeQueryResponse();
        private AlipayTradeRefundResponse refundResponse = new AlipayTradeRefundResponse();
        private AlipayTradeCloseResponse closeResponse = new AlipayTradeCloseResponse();

        private AlipayTradePrecreateRequest lastPrecreate;
        private AlipayTradePagePayRequest lastPagePay;
        private AlipayTradeWapPayRequest lastWapPay;
        private AlipayTradeRefundRequest lastRefund;

        @Override
        public <T extends com.alipay.api.AlipayResponse> T execute(com.alipay.api.AlipayRequest<T> request) throws AlipayApiException {
            if (request instanceof AlipayTradePrecreateRequest precreateRequest) {
                lastPrecreate = precreateRequest;
                @SuppressWarnings("unchecked")
                T cast = (T) precreateResponse;
                return cast;
            }
            if (request instanceof AlipayTradeQueryRequest) {
                @SuppressWarnings("unchecked")
                T cast = (T) queryResponse;
                return cast;
            }
            if (request instanceof AlipayTradeRefundRequest refundRequest) {
                lastRefund = refundRequest;
                @SuppressWarnings("unchecked")
                T cast = (T) refundResponse;
                return cast;
            }
            if (request instanceof AlipayTradeCloseRequest) {
                @SuppressWarnings("unchecked")
                T cast = (T) closeResponse;
                return cast;
            }
            throw new AlipayApiException("unexpected execute request");
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T execute(com.alipay.api.AlipayRequest<T> request, String authToken) throws AlipayApiException {
            return execute(request);
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T execute(com.alipay.api.AlipayRequest<T> request, String authToken, String appAuthToken) throws AlipayApiException {
            return execute(request);
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T execute(com.alipay.api.AlipayRequest<T> request, String authToken, String appAuthToken, String targetAppId) throws AlipayApiException {
            return execute(request);
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T pageExecute(com.alipay.api.AlipayRequest<T> request) throws AlipayApiException {
            if (request instanceof AlipayTradePagePayRequest pagePayRequest) {
                lastPagePay = pagePayRequest;
                @SuppressWarnings("unchecked")
                T cast = (T) pagePayResponse;
                return cast;
            }
            if (request instanceof AlipayTradeWapPayRequest wapPayRequest) {
                lastWapPay = wapPayRequest;
                @SuppressWarnings("unchecked")
                T cast = (T) wapPayResponse;
                return cast;
            }
            throw new AlipayApiException("unexpected pageExecute request");
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T sdkExecute(com.alipay.api.AlipayRequest<T> request) throws AlipayApiException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T pageExecute(com.alipay.api.AlipayRequest<T> request, String httpMethod) throws AlipayApiException {
            return pageExecute(request);
        }

        @Override
        public <TR extends com.alipay.api.AlipayResponse, T extends com.alipay.api.AlipayRequest<TR>> TR parseAppSyncResult(Map<String, String> map, Class<T> aClass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.alipay.api.BatchAlipayResponse execute(com.alipay.api.BatchAlipayRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T certificateExecute(com.alipay.api.AlipayRequest<T> request) throws AlipayApiException {
            return execute(request);
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T certificateExecute(com.alipay.api.AlipayRequest<T> request, String authToken) throws AlipayApiException {
            return execute(request);
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T certificateExecute(com.alipay.api.AlipayRequest<T> request, String authToken, String appAuthToken) throws AlipayApiException {
            return execute(request);
        }

        @Override
        public <T extends com.alipay.api.AlipayResponse> T certificateExecute(com.alipay.api.AlipayRequest<T> request, String authToken, String appAuthToken, String targetAppId) throws AlipayApiException {
            return execute(request);
        }
    }
}
