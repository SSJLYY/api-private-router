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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripePaymentClientTest {

    @Test
    void createOrderBuildsPaymentIntentForStripeCheckout() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"id":"pi_test_1","client_secret":"pi_test_secret","status":"requires_payment_method"}
                """);
        StripePaymentClient client = new StripePaymentClient(new ObjectMapper(), httpClient);
        ProviderInstanceResponse provider = provider(List.of("card", "link", "wxpay"));

        StripePaymentClient.StripeCreateOrderResult result = client.createOrder(
                provider,
                order("stripe", "pi_test_1"),
                new CreateOrderRequest(66.8, "stripe", null, null, null, null, "balance", null, false),
                "api-private-router 66.8 CNY"
        );

        assertEquals("pi_test_1", result.tradeNo());
        assertEquals("pi_test_secret", result.clientSecret());
        assertEquals("POST", httpClient.lastRequest.method());
        assertEquals("https://api.stripe.com/v1/payment_intents", httpClient.lastRequest.uri().toString());
        String body = httpClient.lastBody;
        assertTrue(body.contains("payment_method_types%5B0%5D=card"));
        assertTrue(body.contains("payment_method_types%5B1%5D=link"));
        assertTrue(body.contains("payment_method_types%5B2%5D=wechat_pay"));
        assertTrue(body.contains("payment_method_options%5Bwechat_pay%5D%5Bclient%5D=web"));
        assertEquals("pi-JAVA-ORDER-1", httpClient.lastRequest.headers().firstValue("Idempotency-Key").orElse(""));
    }

    @Test
    void createOrderUsesWechatPayForVisibleWechatMethod() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"id":"pi_test_2","client_secret":"pi_test_secret_2","status":"requires_action"}
                """);
        StripePaymentClient client = new StripePaymentClient(new ObjectMapper(), httpClient);

        client.createOrder(
                provider(List.of("card", "alipay")),
                order("wxpay", "pi_test_2"),
                new CreateOrderRequest(88.0, "wxpay", null, null, null, null, "balance", null, true),
                "api-private-router 88 CNY"
        );

        assertTrue(httpClient.lastBody.contains("payment_method_types%5B0%5D=wechat_pay"));
        assertTrue(httpClient.lastBody.contains("payment_method_options%5Bwechat_pay%5D%5Bclient%5D=mobile_web"));
    }

    @Test
    void queryOrderReadsPaymentIntentState() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"id":"pi_query_1","status":"succeeded","amount":6880,"created":1710000000}
                """);
        StripePaymentClient client = new StripePaymentClient(new ObjectMapper(), httpClient);

        StripePaymentClient.StripeQueryOrderResult result = client.queryOrder(
                provider(List.of("card")),
                order("stripe", "pi_query_1")
        );

        assertEquals("pi_query_1", result.tradeNo());
        assertEquals("paid", result.status());
        assertEquals(68.8, result.amount());
        assertEquals("GET", httpClient.lastRequest.method());
        assertEquals("https://api.stripe.com/v1/payment_intents/pi_query_1", httpClient.lastRequest.uri().toString());
    }

    @Test
    void refundCreatesStripeRefund() {
        RecordingHttpClient httpClient = new RecordingHttpClient("""
                {"id":"re_123","status":"succeeded"}
                """);
        StripePaymentClient client = new StripePaymentClient(new ObjectMapper(), httpClient);

        StripePaymentClient.StripeRefundResult result = client.refund(
                provider(List.of("card")),
                order("stripe", "pi_refund_1"),
                66.8,
                "user requested"
        );

        assertEquals("re_123", result.refundId());
        assertEquals("success", result.status());
        assertEquals("https://api.stripe.com/v1/refunds", httpClient.lastRequest.uri().toString());
        assertTrue(httpClient.lastBody.contains("payment_intent=pi_refund_1"));
        assertTrue(httpClient.lastBody.contains("amount=6680"));
    }

    private ProviderInstanceResponse provider(List<String> supportedTypes) {
        return new ProviderInstanceResponse(
                8L,
                "stripe",
                "Stripe",
                Map.of(
                        "secretKey", "sk_test_123",
                        "publishableKey", "pk_test_123"
                ),
                supportedTypes,
                true,
                "redirect",
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
                "8",
                "stripe",
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
            this.lastBody = extractBody(request);
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
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }

        private String extractBody(HttpRequest request) {
            CapturingBodySubscriber downstream = new CapturingBodySubscriber();
            HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElse(null);
            if (publisher == null) {
                return "";
            }
            publisher.subscribe(downstream);
            return downstream.join();
        }
    }

    private static final class CapturingBodySubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final StringBuilder builder = new StringBuilder();

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer item) {
            builder.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(builder.toString());
        }

        private String join() {
            return result.join();
        }
    }
}
