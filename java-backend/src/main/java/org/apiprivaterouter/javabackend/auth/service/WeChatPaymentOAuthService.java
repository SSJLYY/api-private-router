package org.apiprivaterouter.javabackend.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.payment.service.PaymentResumeTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WeChatPaymentOAuthService {

    private static final String COOKIE_PATH = "/api/v1/auth/oauth/wechat/payment";
    private static final int COOKIE_MAX_AGE_SECONDS = 10 * 60;
    private static final String COOKIE_STATE = "wechat_payment_oauth_state";
    private static final String COOKIE_REDIRECT = "wechat_payment_oauth_redirect";
    private static final String COOKIE_CONTEXT = "wechat_payment_oauth_context";
    private static final String COOKIE_SCOPE = "wechat_payment_oauth_scope";
    private static final String FRONTEND_CALLBACK = "/auth/wechat/payment/callback";
    private static final String DEFAULT_REDIRECT = "/purchase";
    private static final String AUTH_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";

    private final ObjectMapper objectMapper;
    private final WeChatConnectConfigService configService;
    private final PaymentResumeTokenService resumeTokenService;
    private final HttpClient httpClient;

    public WeChatPaymentOAuthService(
            ObjectMapper objectMapper,
            WeChatConnectConfigService configService,
            PaymentResumeTokenService resumeTokenService
    ) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.resumeTokenService = resumeTokenService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public StartResult buildStartResult(HttpServletRequest request, String paymentType, String redirect, String amount, String orderType, Long planId, String scope) {
        WeChatConnectConfigService.WeChatConnectConfig config = configService.getRequiredConfig();
        if (!config.supportsMode("mp")) {
            throw new StructuredApiErrorException(
                    503,
                    "WECHAT_PAYMENT_MP_NOT_CONFIGURED",
                    "wechat in-app payment requires a complete WeChat MP OAuth credential"
            );
        }
        String normalizedPaymentType = normalizeWeChatPaymentType(paymentType);
        String normalizedRedirect = normalizeRedirectPath(redirect);
        String normalizedScope = PaymentResumeTokenService.normalizeScope(scope);
        String state = UUID.randomUUID().toString().replace("-", "");
        String callbackUrl = resolvePaymentCallbackUrl(config, request);
        String authorizeUrl = buildAuthorizeUrl(
                config.appIdForMode("mp"),
                callbackUrl,
                normalizedScope,
                state
        );
        WeChatPaymentContext context = new WeChatPaymentContext(
                normalizedPaymentType,
                blankToNull(amount),
                blankToNull(orderType),
                planId != null && planId > 0 ? planId : null
        );
        return new StartResult(
                authorizeUrl,
                responseCookie(COOKIE_STATE, encodeCookieValue(state), isSecure(request)),
                responseCookie(COOKIE_REDIRECT, encodeCookieValue(normalizedRedirect), isSecure(request)),
                responseCookie(COOKIE_CONTEXT, encodeCookieValue(writeJson(context)), isSecure(request)),
                responseCookie(COOKIE_SCOPE, encodeCookieValue(normalizedScope), isSecure(request))
        );
    }

    public CallbackResult handleCallback(HttpServletRequest request, String code, String state) {
        String normalizedCode = code == null ? "" : code.trim();
        String normalizedState = state == null ? "" : state.trim();
        if (normalizedCode.isEmpty() || normalizedState.isEmpty()) {
            throw new StructuredApiErrorException(400, "missing_params", "missing code/state");
        }

        String expectedState = decodeCookieValue(readCookie(request, COOKIE_STATE));
        if (expectedState.isEmpty() || !MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8), normalizedState.getBytes(StandardCharsets.UTF_8))) {
            throw new StructuredApiErrorException(400, "invalid_state", "invalid oauth state");
        }

        String redirectTo = normalizeRedirectPath(decodeCookieValue(readCookie(request, COOKIE_REDIRECT)));
        WeChatPaymentContext context = readContext(decodeCookieValue(readCookie(request, COOKIE_CONTEXT)));
        String scope = PaymentResumeTokenService.normalizeScope(decodeCookieValue(readCookie(request, COOKIE_SCOPE)));
        WeChatConnectConfigService.WeChatConnectConfig config = configService.getRequiredConfig();
        if (!config.supportsMode("mp")) {
            throw new StructuredApiErrorException(
                    503,
                    "WECHAT_PAYMENT_MP_NOT_CONFIGURED",
                    "wechat in-app payment requires a complete WeChat MP OAuth credential"
            );
        }
        WeChatAccessTokenResponse tokenResponse = exchangeCode(
                config.appIdForMode("mp"),
                config.appSecretForMode("mp"),
                resolvePaymentCallbackUrl(config, request),
                normalizedCode
        );
        String openid = tokenResponse.openid() == null ? "" : tokenResponse.openid().trim();
        if (openid.isEmpty()) {
            throw new StructuredApiErrorException(400, "missing_openid", "missing openid");
        }
        String resolvedScope = tokenResponse.scope() == null || tokenResponse.scope().isBlank()
                ? scope
                : tokenResponse.scope().trim();
        String resumeToken = resumeTokenService.createWeChatPaymentResumeToken(
                new PaymentResumeTokenService.WeChatPaymentResumeClaims(
                        null,
                        openid,
                        context.paymentType(),
                        context.amount(),
                        context.orderType(),
                        context.planId(),
                        redirectTo,
                        resolvedScope,
                        null,
                        null
                )
        );
        String redirectUrl = FRONTEND_CALLBACK
                + "#wechat_resume_token=" + urlEncode(resumeToken)
                + "&redirect=" + urlEncode(redirectTo);
        return new CallbackResult(
                redirectUrl,
                clearCookie(COOKIE_STATE, isSecure(request)),
                clearCookie(COOKIE_REDIRECT, isSecure(request)),
                clearCookie(COOKIE_CONTEXT, isSecure(request)),
                clearCookie(COOKIE_SCOPE, isSecure(request))
        );
    }

    public String normalizeWeChatPaymentType(String paymentType) {
        String normalized = PaymentResumeTokenService.normalizeVisibleMethod(paymentType);
        if (!"wxpay".equals(normalized)) {
            throw new StructuredApiErrorException(400, "INVALID_PAYMENT_TYPE", "Invalid payment type");
        }
        return normalized;
    }

    public String normalizeRedirectPath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return DEFAULT_REDIRECT;
        }
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("://") || value.contains("\n") || value.contains("\r")) {
            return DEFAULT_REDIRECT;
        }
        if ("/payment".equals(value)) {
            return "/purchase";
        }
        if (value.startsWith("/payment?")) {
            return "/purchase" + value.substring("/payment".length());
        }
        return value;
    }

    public String buildAuthorizeUrl(String paymentType, String redirect, String amount, String orderType, Long planId, String scope) {
        StringBuilder builder = new StringBuilder("/api/v1/auth/oauth/wechat/payment/start");
        builder.append("?payment_type=").append(urlEncode(normalizeWeChatPaymentType(paymentType)));
        builder.append("&redirect=").append(urlEncode(normalizeRedirectPath(redirect)));
        String normalizedScope = PaymentResumeTokenService.normalizeScope(scope);
        if (!normalizedScope.isBlank()) {
            builder.append("&scope=").append(urlEncode(normalizedScope));
        }
        if (amount != null && !amount.trim().isEmpty()) {
            builder.append("&amount=").append(urlEncode(amount.trim()));
        }
        if (orderType != null && !orderType.trim().isEmpty()) {
            builder.append("&order_type=").append(urlEncode(orderType.trim()));
        }
        if (planId != null && planId > 0) {
            builder.append("&plan_id=").append(planId);
        }
        return builder.toString();
    }

    private WeChatAccessTokenResponse exchangeCode(String appId, String appSecret, String redirectUri, String code) {
        String url = ACCESS_TOKEN_URL
                + "?appid=" + urlEncode(appId)
                + "&secret=" + urlEncode(appSecret)
                + "&code=" + urlEncode(code)
                + "&grant_type=authorization_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            WeChatAccessTokenResponse body = objectMapper.readValue(response.body(), WeChatAccessTokenResponse.class);
            if (body.errCode() != null && body.errCode() != 0) {
                throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
            }
            return body;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StructuredApiErrorException(502, "token_exchange_failed", "failed to exchange oauth code");
        }
    }

    private WeChatPaymentContext readContext(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new WeChatPaymentContext("wxpay", null, null, null);
        }
        try {
            WeChatPaymentContext parsed = objectMapper.readValue(raw, WeChatPaymentContext.class);
            return new WeChatPaymentContext(
                    normalizeWeChatPaymentType(parsed.paymentType()),
                    blankToNull(parsed.amount()),
                    blankToNull(parsed.orderType()),
                    parsed.planId() != null && parsed.planId() > 0 ? parsed.planId() : null
            );
        } catch (IOException ex) {
            throw new StructuredApiErrorException(400, "invalid_context", "invalid oauth context");
        }
    }

    private String resolvePaymentCallbackUrl(WeChatConnectConfigService.WeChatConnectConfig config, HttpServletRequest request) {
        if (config.redirectUrl() != null && !config.redirectUrl().isBlank()) {
            return config.redirectUrl().trim();
        }
        String apiBaseUrl = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().trim();
        if (!apiBaseUrl.isBlank()) {
            try {
                URI apiUri = URI.create(apiBaseUrl);
                if (apiUri.getScheme() != null && apiUri.getHost() != null) {
                    String path = apiUri.getPath() == null ? "" : apiUri.getPath();
                    if (path.endsWith("/api/v1")) {
                        return apiUri.getScheme() + "://" + apiUri.getAuthority() + path + "/auth/oauth/wechat/payment/callback";
                    }
                    return apiUri.getScheme() + "://" + apiUri.getAuthority() + path + "/api/v1/auth/oauth/wechat/payment/callback";
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to request-derived URL.
            }
        }
        String scheme = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme(), "http");
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"), request.getServerName());
        return scheme + "://" + host + "/api/v1/auth/oauth/wechat/payment/callback";
    }

    private String buildAuthorizeUrl(String appId, String callbackUrl, String scope, String state) {
        return AUTH_URL
                + "?appid=" + urlEncode(appId)
                + "&redirect_uri=" + urlEncode(callbackUrl)
                + "&response_type=code"
                + "&scope=" + urlEncode(scope)
                + "&state=" + urlEncode(state)
                + "#wechat_redirect";
    }

    private boolean isSecure(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.trim().isEmpty()) {
            return "https".equalsIgnoreCase(forwardedProto.trim());
        }
        return request.isSecure();
    }

    private ResponseCookie responseCookie(String name, String value, boolean secure) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .maxAge(Duration.ofSeconds(COOKIE_MAX_AGE_SECONDS))
                .path(COOKIE_PATH)
                .build();
    }

    private ResponseCookie clearCookie(String name, boolean secure) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(secure)
                .maxAge(Duration.ZERO)
                .path(COOKIE_PATH)
                .build();
    }

    private String encodeCookieValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCookieValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(raw.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return "";
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return "";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new StructuredApiErrorException(500, "OAUTH_CONTEXT_ENCODE_FAILED", "failed to encode oauth context");
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    public record StartResult(
            String authorizeUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie contextCookie,
            ResponseCookie scopeCookie
    ) {
    }

    public record CallbackResult(
            String redirectUrl,
            ResponseCookie stateCookie,
            ResponseCookie redirectCookie,
            ResponseCookie contextCookie,
            ResponseCookie scopeCookie
    ) {
    }

    public record WeChatPaymentContext(
            @JsonProperty("payment_type") String paymentType,
            @JsonProperty("amount") String amount,
            @JsonProperty("order_type") String orderType,
            @JsonProperty("plan_id") Long planId
    ) {
    }

    public record WeChatAccessTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("openid") String openid,
            @JsonProperty("scope") String scope,
            @JsonProperty("errcode") Integer errCode,
            @JsonProperty("errmsg") String errMsg
    ) {
    }
}
