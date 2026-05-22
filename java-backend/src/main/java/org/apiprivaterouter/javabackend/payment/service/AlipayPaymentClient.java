package org.apiprivaterouter.javabackend.payment.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
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
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.model.CreateOrderRequest;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

@Service
public class AlipayPaymentClient {

    private static final String DEFAULT_SERVER_URL = "https://openapi.alipay.com/gateway.do";
    private static final String DEFAULT_FORMAT = "json";
    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final String DEFAULT_SIGN_TYPE = "RSA2";
    private static final String PRODUCT_CODE_PRECREATE = "FACE_TO_FACE_PAYMENT";
    private static final String PRODUCT_CODE_WAP = "QUICK_WAP_WAY";
    private static final String PRODUCT_CODE_PAGE = "FAST_INSTANT_TRADE_PAY";
    private static final String TRADE_STATUS_SUCCESS = "TRADE_SUCCESS";
    private static final String TRADE_STATUS_FINISHED = "TRADE_FINISHED";
    private static final String TRADE_STATUS_CLOSED = "TRADE_CLOSED";
    private static final String FUND_CHANGE_YES = "Y";
    private static final String TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";

    private final AlipayClientFactory clientFactory;

    public AlipayPaymentClient() {
        this(new DefaultAlipayClientFactory());
    }

    AlipayPaymentClient(AlipayClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public AlipayCreateOrderResult createOrder(
            ProviderInstanceResponse provider,
            PaymentOrderResponse order,
            CreateOrderRequest request,
            String notifyUrl,
            String returnUrl,
            String subject
    ) {
        AlipayClient client = createClient(provider);
        String effectiveNotifyUrl = firstNonBlank(notifyUrl, provider.config().get("notifyUrl"));
        String effectiveReturnUrl = firstNonBlank(returnUrl, provider.config().get("returnUrl"));
        requireNonBlank(effectiveNotifyUrl, "notifyUrl");

        if (Boolean.TRUE.equals(request.is_mobile())) {
            return createWapTrade(client, order, subject, effectiveNotifyUrl, effectiveReturnUrl);
        }
        return createDesktopTrade(client, order, subject, effectiveNotifyUrl, effectiveReturnUrl);
    }

    public AlipayQueryOrderResult queryOrder(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        AlipayClient client = createClient(provider);
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(order.out_trade_no());
        request.setBizModel(model);

        AlipayTradeQueryResponse response = execute(() -> client.execute(request), "alipay TradeQuery");
        if (!response.isSuccess()) {
            if (isTradeNotExist(response)) {
                return new AlipayQueryOrderResult(order.out_trade_no(), "pending", 0.0, null);
            }
            throw gatewayError("alipay TradeQuery failed: " + responseErrorMessage(response));
        }
        return new AlipayQueryOrderResult(
                firstNonBlank(response.getTradeNo(), order.out_trade_no()),
                normalizeTradeStatus(response.getTradeStatus()),
                firstPositiveAmount(
                        response.getTotalAmount(),
                        response.getReceiptAmount(),
                        response.getBuyerPayAmount(),
                        response.getInvoiceAmount()
                ),
                formatDate(response.getSendPayDate())
        );
    }

    public void cancelPayment(ProviderInstanceResponse provider, PaymentOrderResponse order) {
        AlipayClient client = createClient(provider);
        AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
        AlipayTradeCloseModel model = new AlipayTradeCloseModel();
        model.setOutTradeNo(order.out_trade_no());
        request.setBizModel(model);

        AlipayTradeCloseResponse response = execute(() -> client.execute(request), "alipay TradeClose");
        if (!response.isSuccess() && !isTradeNotExist(response)) {
            throw gatewayError("alipay TradeClose failed: " + responseErrorMessage(response));
        }
    }

    public AlipayRefundResult refund(ProviderInstanceResponse provider, PaymentOrderResponse order, double amount, String reason) {
        AlipayClient client = createClient(provider);
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(order.out_trade_no());
        model.setRefundAmount(formatAmount(amount));
        model.setRefundReason(trimToEmpty(reason));
        model.setOutRequestNo(order.out_trade_no() + "-refund-" + UUID.randomUUID().toString().replace("-", ""));
        request.setBizModel(model);

        AlipayTradeRefundResponse response = execute(() -> client.execute(request), "alipay TradeRefund");
        if (!response.isSuccess()) {
            throw gatewayError("alipay TradeRefund failed: " + responseErrorMessage(response));
        }
        return new AlipayRefundResult(
                firstNonBlank(response.getTradeNo(), order.out_trade_no() + "-refund"),
                FUND_CHANGE_YES.equalsIgnoreCase(trimToEmpty(response.getFundChange())) ? "success" : "pending"
        );
    }

    private AlipayCreateOrderResult createDesktopTrade(
            AlipayClient client,
            PaymentOrderResponse order,
            String subject,
            String notifyUrl,
            String returnUrl
    ) {
        try {
            return createPrecreateTrade(client, order, subject, notifyUrl);
        } catch (StructuredApiErrorException ex) {
            AlipayCreateOrderResult fallback = createPagePayTrade(client, order, subject, notifyUrl, returnUrl);
            return new AlipayCreateOrderResult(fallback.payUrl(), fallback.qrCode(), fallback.tradeNo(), "qrcode");
        }
    }

    private AlipayCreateOrderResult createPrecreateTrade(
            AlipayClient client,
            PaymentOrderResponse order,
            String subject,
            String notifyUrl
    ) {
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        request.setNotifyUrl(notifyUrl);

        AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
        model.setOutTradeNo(order.out_trade_no());
        model.setTotalAmount(formatAmount(order.pay_amount()));
        model.setSubject(subject);
        model.setProductCode(PRODUCT_CODE_PRECREATE);
        request.setBizModel(model);

        AlipayTradePrecreateResponse response = execute(() -> client.execute(request), "alipay TradePreCreate");
        if (!response.isSuccess()) {
            throw gatewayError("alipay TradePreCreate failed: " + responseErrorMessage(response));
        }
        String qrCode = trimToEmpty(response.getQrCode());
        if (qrCode.isBlank()) {
            throw gatewayError("alipay TradePreCreate failed: missing qr_code");
        }
        return new AlipayCreateOrderResult("", qrCode, order.out_trade_no(), "qrcode");
    }

    private AlipayCreateOrderResult createPagePayTrade(
            AlipayClient client,
            PaymentOrderResponse order,
            String subject,
            String notifyUrl,
            String returnUrl
    ) {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(notifyUrl);
        if (!trimToEmpty(returnUrl).isBlank()) {
            request.setReturnUrl(returnUrl);
        }

        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(order.out_trade_no());
        model.setTotalAmount(formatAmount(order.pay_amount()));
        model.setSubject(subject);
        model.setProductCode(PRODUCT_CODE_PAGE);
        request.setBizModel(model);

        AlipayTradePagePayResponse response = execute(() -> client.pageExecute(request, "GET"), "alipay TradePagePay");
        String payUrl = extractPageRedirectUrl(response);
        if (payUrl.isBlank()) {
            throw gatewayError("alipay TradePagePay failed: missing redirection body");
        }
        return new AlipayCreateOrderResult(payUrl, payUrl, order.out_trade_no(), "redirect");
    }

    private AlipayCreateOrderResult createWapTrade(
            AlipayClient client,
            PaymentOrderResponse order,
            String subject,
            String notifyUrl,
            String returnUrl
    ) {
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        request.setNotifyUrl(notifyUrl);
        if (!trimToEmpty(returnUrl).isBlank()) {
            request.setReturnUrl(returnUrl);
        }

        AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
        model.setOutTradeNo(order.out_trade_no());
        model.setTotalAmount(formatAmount(order.pay_amount()));
        model.setSubject(subject);
        model.setProductCode(PRODUCT_CODE_WAP);
        if (!trimToEmpty(returnUrl).isBlank()) {
            model.setQuitUrl(returnUrl);
        }
        request.setBizModel(model);

        AlipayTradeWapPayResponse response = execute(() -> client.pageExecute(request, "GET"), "alipay TradeWapPay");
        String payUrl = extractPageRedirectUrl(response);
        if (payUrl.isBlank()) {
            throw gatewayError("alipay TradeWapPay failed: missing redirection body");
        }
        return new AlipayCreateOrderResult(payUrl, null, order.out_trade_no(), "redirect");
    }

    private AlipayClient createClient(ProviderInstanceResponse provider) {
        Map<String, String> config = provider.config();
        String appId = requireConfig(config, "appId");
        String privateKey = requireConfig(config, "privateKey");
        String publicKey = firstNonBlank(config.get("publicKey"), config.get("alipayPublicKey"));
        if (publicKey.isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "alipay config missing required key: publicKey");
        }

        AlipayConfig sdkConfig = new AlipayConfig();
        sdkConfig.setServerUrl(firstNonBlank(config.get("serverUrl"), DEFAULT_SERVER_URL));
        sdkConfig.setAppId(appId);
        sdkConfig.setPrivateKey(privateKey);
        sdkConfig.setFormat(DEFAULT_FORMAT);
        sdkConfig.setCharset(firstNonBlank(config.get("charset"), DEFAULT_CHARSET));
        sdkConfig.setSignType(firstNonBlank(config.get("signType"), DEFAULT_SIGN_TYPE));
        sdkConfig.setAlipayPublicKey(publicKey);
        sdkConfig.setConnectTimeout(parsePositiveInt(config.get("connectTimeout"), 10000));
        sdkConfig.setReadTimeout(parsePositiveInt(config.get("readTimeout"), 10000));
        try {
            return clientFactory.create(sdkConfig);
        } catch (AlipayApiException ex) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "alipay client init failed: " + trimToEmpty(ex.getErrMsg()));
        }
    }

    private <T> T execute(AlipayCall<T> call, String action) {
        try {
            return call.run();
        } catch (AlipayApiException ex) {
            throw gatewayError(action + ": " + firstNonBlank(ex.getErrMsg(), ex.getMessage()));
        }
    }

    private StructuredApiErrorException gatewayError(String message) {
        return new StructuredApiErrorException(502, "PAYMENT_GATEWAY_ERROR", message);
    }

    private String extractPageRedirectUrl(Object response) {
        if (response instanceof AlipayTradePagePayResponse pageResponse) {
            return firstNonBlank(pageResponse.getBody(), pageResponse.getPageRedirectionData());
        }
        if (response instanceof AlipayTradeWapPayResponse wapResponse) {
            return firstNonBlank(wapResponse.getBody(), wapResponse.getPageRedirectionData());
        }
        return "";
    }

    private boolean isTradeNotExist(AlipayTradeQueryResponse response) {
        return containsTradeNotExist(response.getSubCode()) || containsTradeNotExist(response.getMsg()) || containsTradeNotExist(response.getSubMsg());
    }

    private boolean isTradeNotExist(AlipayTradeCloseResponse response) {
        return containsTradeNotExist(response.getSubCode()) || containsTradeNotExist(response.getMsg()) || containsTradeNotExist(response.getSubMsg());
    }

    private boolean containsTradeNotExist(String value) {
        return trimToEmpty(value).contains(TRADE_NOT_EXIST);
    }

    private String responseErrorMessage(com.alipay.api.AlipayResponse response) {
        String subCode = trimToEmpty(response.getSubCode());
        String subMsg = trimToEmpty(response.getSubMsg());
        String code = trimToEmpty(response.getCode());
        String msg = trimToEmpty(response.getMsg());
        if (!subCode.isBlank() && !subMsg.isBlank()) {
            return subCode + ": " + subMsg;
        }
        if (!subCode.isBlank()) {
            return subCode;
        }
        if (!subMsg.isBlank()) {
            return subMsg;
        }
        if (!code.isBlank() && !msg.isBlank()) {
            return code + ": " + msg;
        }
        if (!msg.isBlank()) {
            return msg;
        }
        return code;
    }

    private String normalizeTradeStatus(String tradeStatus) {
        String normalized = trimToEmpty(tradeStatus).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case TRADE_STATUS_SUCCESS, TRADE_STATUS_FINISHED -> "paid";
            case TRADE_STATUS_CLOSED -> "failed";
            default -> "pending";
        };
    }

    private double firstPositiveAmount(String... values) {
        for (String value : values) {
            double parsed = parseAmount(value);
            if (parsed > 0) {
                return parsed;
            }
        }
        return 0.0;
    }

    private double parseAmount(String raw) {
        try {
            return trimToEmpty(raw).isBlank() ? 0.0 : Double.parseDouble(trimToEmpty(raw));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String requireConfig(Map<String, String> config, String key) {
        String value = trimToEmpty(config.get(key));
        if (value.isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "alipay config missing required key: " + key);
        }
        return value;
    }

    private void requireNonBlank(String value, String key) {
        if (trimToEmpty(value).isBlank()) {
            throw new StructuredApiErrorException(503, "PAYMENT_PROVIDER_MISCONFIGURED", "alipay config missing required key: " + key);
        }
    }

    private String formatAmount(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private String formatDate(Date value) {
        if (value == null) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(value);
    }

    private int parsePositiveInt(String raw, int defaultValue) {
        try {
            int parsed = Integer.parseInt(trimToEmpty(raw));
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (!trimToEmpty(first).isBlank()) {
            return first.trim();
        }
        return trimToEmpty(second);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @FunctionalInterface
    interface AlipayCall<T> {
        T run() throws AlipayApiException;
    }

    interface AlipayClientFactory {
        AlipayClient create(AlipayConfig config) throws AlipayApiException;
    }

    static final class DefaultAlipayClientFactory implements AlipayClientFactory {
        @Override
        public AlipayClient create(AlipayConfig config) throws AlipayApiException {
            return new DefaultAlipayClient(config);
        }
    }

    public record AlipayCreateOrderResult(
            String payUrl,
            String qrCode,
            String tradeNo,
            String paymentMode
    ) {
    }

    public record AlipayQueryOrderResult(
            String tradeNo,
            String status,
            double amount,
            String paidAt
    ) {
        public boolean paid() {
            return "paid".equalsIgnoreCase(status);
        }
    }

    public record AlipayRefundResult(
            String refundId,
            String status
    ) {
    }
}
