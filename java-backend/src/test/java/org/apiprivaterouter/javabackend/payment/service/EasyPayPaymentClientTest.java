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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasyPayPaymentClientTest {

    @Test
    void signMatchesGoBehavior() {
        EasyPayPaymentClient client = new EasyPayPaymentClient(new ObjectMapper(), new StubHttpClient(""));

        Map<String, String> base = Map.of(
                "pid", "1001",
                "type", "alipay",
                "out_trade_no", "JAVA-ORDER-1",
                "money", "12.30"
        );
        String sign = client.sign(base, "secret-key");
        String signWithIgnoredFields = client.sign(Map.of(
                "pid", "1001",
                "type", "alipay",
                "out_trade_no", "JAVA-ORDER-1",
                "money", "12.30",
                "sign", "ignored",
                "sign_type", "MD5"
        ), "secret-key");

        assertEquals(32, sign.length());
        assertEquals(sign, signWithIgnoredFields);
    }

    @Test
    void createOrderUsesSubmitUrlForPopupMode() {
        EasyPayPaymentClient client = new EasyPayPaymentClient(new ObjectMapper(), new StubHttpClient(""));
        ProviderInstanceResponse provider = new ProviderInstanceResponse(
                2L,
                "easypay",
                "EasyPay",
                Map.of(
                        "pid", "1001",
                        "pkey", "secret-key",
                        "apiBase", "https://pay.example.com/mapi.php",
                        "cidAlipay", "c-alipay"
                ),
                List.of("alipay", "wxpay"),
                true,
                "popup",
                false,
                false,
                "",
                0
        );
        PaymentOrderResponse order = order("alipay", 66.8, "JAVA-ORDER-1");

        EasyPayPaymentClient.EasyPayCreateOrderResult result = client.createOrder(
                provider,
                order,
                new CreateOrderRequest(66.8, "alipay", null, null, "https://app.example.com/payment/result", null, "balance", null, false),
                "https://api.example.com/api/v1/payment/webhook/easypay",
                "https://app.example.com/payment/result?order_id=1",
                "203.0.113.10",
                "api-private-router 66.8 CNY"
        );

        assertEquals("popup", result.paymentMode());
        assertTrue(result.payUrl().startsWith("https://pay.example.com/submit.php?"));
        assertTrue(result.payUrl().contains("cid=c-alipay"));
        assertEquals("", result.tradeNo());
        assertNull(result.qrCode());
    }

    @Test
    void createOrderPrefersMobilePayUrl2ForQrcodeMode() {
        HttpClient httpClient = new StubHttpClient("""
                {"code":1,"msg":"ok","trade_no":"ep-123","payurl":"https://pay.example.com/desktop","payurl2":"https://pay.example.com/mobile","qrcode":"weixin://wxpay/bizpayurl?pr=qr-1"}
                """);
        EasyPayPaymentClient client = new EasyPayPaymentClient(new ObjectMapper(), httpClient);
        ProviderInstanceResponse provider = new ProviderInstanceResponse(
                2L,
                "easypay",
                "EasyPay",
                Map.of(
                        "pid", "1001",
                        "pkey", "secret-key",
                        "apiBase", "https://pay.example.com"
                ),
                List.of("alipay", "wxpay"),
                true,
                "qrcode",
                false,
                false,
                "",
                0
        );

        EasyPayPaymentClient.EasyPayCreateOrderResult result = client.createOrder(
                provider,
                order("wxpay", 88.0, "JAVA-ORDER-2"),
                new CreateOrderRequest(88.0, "wxpay", null, null, "https://app.example.com/payment/result", null, "balance", null, true),
                "https://api.example.com/api/v1/payment/webhook/easypay",
                "https://app.example.com/payment/result?order_id=2",
                "198.51.100.3",
                "api-private-router 88 CNY"
        );

        assertEquals("qrcode", result.paymentMode());
        assertEquals("ep-123", result.tradeNo());
        assertEquals("https://pay.example.com/mobile", result.payUrl());
        assertEquals("weixin://wxpay/bizpayurl?pr=qr-1", result.qrCode());
    }

    @Test
    void queryOrderMapsPaidStatus() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"code":1,"msg":"ok","status":1,"money":"88.00"}
                """);
        EasyPayPaymentClient client = new EasyPayPaymentClient(new ObjectMapper(), httpClient);

        EasyPayPaymentClient.EasyPayQueryOrderResult result = client.queryOrder(provider("qrcode"), order("wxpay", 88.0, "JAVA-ORDER-2"));

        assertTrue(result.paid());
        assertEquals("JAVA-ORDER-2", result.tradeNo());
        assertEquals(88.0, result.amount());
        assertEquals("https://pay.example.com/api.php", httpClient.lastRequest.uri().toString());
        assertTrue(httpClient.lastBody.contains("act=order"));
        assertTrue(httpClient.lastBody.contains("out_trade_no=JAVA-ORDER-2"));
    }

    @Test
    void refundRetriesWithTradeNoWhenOutTradeNoNotFound() {
        RecordingHttpClient httpClient = new RecordingHttpClient(
                """
                        {"code":0,"msg":"\\u8ba2\\u5355\\u7f16\\u53f7\\u4e0d\\u5b58\\u5728"}
                        """,
                """
                        {"code":"1","msg":"ok"}
                        """
        );
        EasyPayPaymentClient client = new EasyPayPaymentClient(new ObjectMapper(), httpClient);

        PaymentOrderResponse order = order("alipay", 12.0, "JAVA-ORDER-3", "EP-TRADE-3");
        EasyPayPaymentClient.EasyPayRefundResult result = client.refund(provider("qrcode"), order, 12.0, "user request");

        assertEquals("success", result.status());
        assertEquals("EP-TRADE-3", result.refundId());
        assertEquals(2, httpClient.bodies.size());
        assertTrue(httpClient.bodies.get(0).contains("out_trade_no=JAVA-ORDER-3"));
        assertTrue(httpClient.bodies.get(1).contains("trade_no=EP-TRADE-3"));
    }

    private PaymentOrderResponse order(String paymentType, double payAmount, String outTradeNo) {
        return order(paymentType, payAmount, outTradeNo, null);
    }

    private PaymentOrderResponse order(String paymentType, double payAmount, String outTradeNo, String paymentTradeNo) {
        return new PaymentOrderResponse(
                1L,
                10L,
                payAmount,
                payAmount,
                0.0,
                paymentType,
                outTradeNo,
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
                "2",
                "easypay",
                paymentTradeNo,
                null,
                null
        );
    }

    private ProviderInstanceResponse provider(String paymentMode) {
        return new ProviderInstanceResponse(
                2L,
                "easypay",
                "EasyPay",
                Map.of(
                        "pid", "1001",
                        "pkey", "secret-key",
                        "apiBase", "https://pay.example.com/mapi.php"
                ),
                List.of("alipay", "wxpay"),
                true,
                paymentMode,
                true,
                true,
                "",
                0
        );
    }

    private static final class StubHttpClient extends HttpClient {
        private final String body;

        private StubHttpClient(String body) {
            this.body = body;
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
            @SuppressWarnings("unchecked")
            T castBody = (T) body;
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
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingHttpClient extends HttpClient {
        private final List<String> responses;
        private final List<String> bodies = new java.util.ArrayList<>();
        private int calls;
        private HttpRequest lastRequest;
        private String lastBody = "";

        private RecordingHttpClient(String... responses) {
            this.responses = List.of(responses);
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
            this.lastBody = request.bodyPublisher()
                    .map(publisher -> {
                        BodySubscriber subscriber = new BodySubscriber();
                        publisher.subscribe(subscriber);
                        return subscriber.body();
                    })
                    .orElse("");
            this.bodies.add(lastBody);
            String body = responses.get(Math.min(calls, responses.size() - 1));
            calls++;
            @SuppressWarnings("unchecked")
            T castBody = (T) body;
            return response(request, castBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }

        private <T> HttpResponse<T> response(HttpRequest request, T body) {
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
                    return body;
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
    }

    private static final class BodySubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final StringBuilder body = new StringBuilder();

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer item) {
            body.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {
        }

        private String body() {
            return body.toString();
        }
    }
}
