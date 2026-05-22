package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;

import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WxpayPaymentClientTest {

    private static final KeyPair TEST_KEY_PAIR = generateKeyPair();
    private static final String TEST_PRIVATE_KEY = privateKeyPem(TEST_KEY_PAIR);
    private static final String TEST_PUBLIC_KEY = publicKeyPem(TEST_KEY_PAIR);

    @Test
    void createJsapiOrderReturnsSignedPayload() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"prepay_id":"wx201410272009395522657a690389285100"}
                """);
        WxpayPaymentClient client = new WxpayPaymentClient(new ObjectMapper(), httpClient);

        WxpayPaymentClient.WxpayCreateOrderResult result = client.createOrder(
                provider(),
                order("wxpay", null),
                new CreateOrderRequest(66.8, "wxpay", "openid-123", null, null, null, "balance", null, true),
                "https://notify.example.com/wxpay",
                "https://app.example.com/payment/result",
                "127.0.0.1",
                "api-private-router 66.8 CNY"
        );

        assertEquals("jsapi_ready", result.resultType());
        assertNotNull(result.jsapiPayload());
        assertEquals("wx-mp-app", result.jsapiPayload().appId());
        assertTrue(result.jsapiPayload().packageValue().startsWith("prepay_id="));
        assertEquals("POST", httpClient.lastRequest.method());
        assertEquals("https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi", httpClient.lastRequest.uri().toString());
        assertTrue(httpClient.lastBody.contains("\"openid\":\"openid-123\""));
    }

    @Test
    void createNativeOrderReturnsQrCode() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"code_url":"weixin://wxpay/bizpayurl?pr=abc123"}
                """);
        WxpayPaymentClient client = new WxpayPaymentClient(new ObjectMapper(), httpClient);

        WxpayPaymentClient.WxpayCreateOrderResult result = client.createOrder(
                provider(),
                order("wxpay", null),
                new CreateOrderRequest(66.8, "wxpay", null, null, null, null, "balance", null, false),
                "https://notify.example.com/wxpay",
                "https://app.example.com/payment/result",
                "127.0.0.1",
                "api-private-router 66.8 CNY"
        );

        assertEquals("weixin://wxpay/bizpayurl?pr=abc123", result.qrCode());
        assertEquals("https://api.mch.weixin.qq.com/v3/pay/transactions/native", httpClient.lastRequest.uri().toString());
    }

    @Test
    void queryOrderMapsSuccessState() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"transaction_id":"42000000000001","trade_state":"SUCCESS","success_time":"2026-05-21T12:00:00+08:00","amount":{"total":6680}}
                """);
        WxpayPaymentClient client = new WxpayPaymentClient(new ObjectMapper(), httpClient);

        WxpayPaymentClient.WxpayQueryOrderResult result = client.queryOrder(provider(), order("wxpay", null));

        assertEquals("42000000000001", result.tradeNo());
        assertEquals("paid", result.status());
        assertEquals(66.8, result.amount());
        assertEquals("GET", httpClient.lastRequest.method());
        assertTrue(httpClient.lastRequest.uri().toString().contains("/v3/pay/transactions/out-trade-no/JAVA-ORDER-1"));
    }

    @Test
    void refundBuildsDomesticRefundRequest() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"refund_id":"50000000382019052709732678859","status":"SUCCESS"}
                """);
        WxpayPaymentClient client = new WxpayPaymentClient(new ObjectMapper(), httpClient);

        WxpayPaymentClient.WxpayRefundResult result = client.refund(provider(), order("wxpay", "42000000000001"), 20.0, "user requested");

        assertEquals("50000000382019052709732678859", result.refundId());
        assertEquals("success", result.status());
        assertEquals("https://api.mch.weixin.qq.com/v3/refund/domestic/refunds", httpClient.lastRequest.uri().toString());
        assertTrue(httpClient.lastBody.contains("\"refund\":2000"));
        assertTrue(httpClient.lastBody.contains("\"total\":6680"));
    }

    private ProviderInstanceResponse provider() {
        return new ProviderInstanceResponse(
                5L,
                "wxpay",
                "WxPay",
                Map.of(
                        "appId", "wx-app",
                        "mpAppId", "wx-mp-app",
                        "mchId", "1900000109",
                        "privateKey", TEST_PRIVATE_KEY,
                        "apiV3Key", "12345678901234567890123456789012",
                        "certSerial", "444F4864EA5F4E95AA4CE95F8B0A0D1C",
                        "publicKey", TEST_PUBLIC_KEY,
                        "publicKeyId", "PUB_KEY_ID_011423",
                        "h5AppName", "api-private-router",
                        "h5AppUrl", "https://app.example.com"
                ),
                List.of("wxpay"),
                true,
                "qrcode",
                true,
                true,
                "",
                0
        );
    }

    private PaymentOrderResponse order(String paymentType, String paymentTradeNo) {
        return new PaymentOrderResponse(
                1L,
                10L,
                66.8,
                66.8,
                0.0,
                paymentType,
                "JAVA-ORDER-1",
                "PENDING",
                "balance",
                "2026-05-20T00:00:00Z",
                "2026-05-20T00:30:00Z",
                null,
                null,
                0.0,
                null,
                null,
                null,
                null,
                null,
                "5",
                "wxpay",
                paymentTradeNo,
                null,
                null
        );
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final List<String> queuedBodies = new ArrayList<>();
        private HttpRequest lastRequest;
        private String lastBody = "";

        private RecordingHttpClient(String body) {
            this.queuedBodies.add(body);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            this.lastRequest = request;
            this.lastBody = StripePaymentClientTestSupport.extractBody(request);
            String responseBody = queuedBodies.isEmpty() ? "{}" : queuedBodies.remove(0);
            @SuppressWarnings("unchecked")
            T castBody = (T) responseBody;
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of(), (a, b) -> true);
                }

                @Override
                public T body() {
                    return castBody;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StripePaymentClientTestSupport {
        private StripePaymentClientTestSupport() {
        }

        private static String extractBody(HttpRequest request) {
            try {
                var publisherOpt = request.bodyPublisher();
                if (publisherOpt.isEmpty()) {
                    return "";
                }
                var publisher = publisherOpt.get();
                var subscriber = new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    private final StringBuilder sb = new StringBuilder();
                    private java.util.concurrent.Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        sb.append(new String(bytes, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onComplete() {
                    }

                    @Override
                    public String toString() {
                        return sb.toString();
                    }
                };
                publisher.subscribe(subscriber);
                Thread.sleep(10L);
                return subscriber.toString();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to generate test private key", ex);
        }
    }

    private static String privateKeyPem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private static String publicKeyPem(KeyPair keyPair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
