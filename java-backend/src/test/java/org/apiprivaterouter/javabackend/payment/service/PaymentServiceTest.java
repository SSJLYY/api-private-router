package org.apiprivaterouter.javabackend.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.auth.service.WeChatConnectConfigService;
import org.apiprivaterouter.javabackend.auth.service.WeChatPaymentOAuthService;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.model.ProviderInstanceResponse;
import org.apiprivaterouter.javabackend.payment.model.RefundRequest;
import org.apiprivaterouter.javabackend.payment.repository.PaymentRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    @Test
    void requestRefundUsesSnapshotProviderInstanceForLegacyOrder() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentService service = new PaymentService(
                repository,
                mock(PaymentResumeTokenService.class),
                mock(WeChatPaymentOAuthService.class),
                mock(WeChatConnectConfigService.class),
                mock(EasyPayPaymentClient.class),
                mock(AlipayPaymentClient.class),
                mock(WxpayPaymentClient.class),
                mock(StripePaymentClient.class),
                mock(PaymentWebhookService.class),
                new JsonHelper(new ObjectMapper())
        );

        PaymentOrderResponse order = order(88L, null, "alipay", "COMPLETED", "balance", 100.0);
        ProviderInstanceResponse provider = provider(12L, "alipay", true, true);
        when(repository.loadOrderByUserAndId(7L, 88L)).thenReturn(Optional.of(order));
        when(repository.loadProviderSnapshot(88L)).thenReturn(Map.of(
                "provider_instance_id", "12",
                "provider_key", "alipay"
        ));
        when(repository.findProviderByInstanceId("12")).thenReturn(Optional.of(provider));
        when(repository.findUserBalance(7L)).thenReturn(Optional.of(300.0));
        when(repository.markRefundRequested(7L, 88L, "need refund", 100.0))
                .thenReturn(Optional.of(order(88L, null, "alipay", "REFUND_REQUESTED", "balance", 100.0)));
        doNothing().when(repository).insertAuditLog(eq(88L), eq("REFUND_REQUESTED"), any(), eq("user:7"));

        PaymentOrderResponse result = service.requestRefund(new CurrentUser(7L, "user@example.test", "user", 1L), 88L, new RefundRequest("need refund"));

        assertEquals("REFUND_REQUESTED", result.status());
        verify(repository).findProviderByInstanceId("12");
        verify(repository).markRefundRequested(7L, 88L, "need refund", 100.0);
    }

    @Test
    void requestRefundRejectsSnapshotProviderMismatch() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentService service = new PaymentService(
                repository,
                mock(PaymentResumeTokenService.class),
                mock(WeChatPaymentOAuthService.class),
                mock(WeChatConnectConfigService.class),
                mock(EasyPayPaymentClient.class),
                mock(AlipayPaymentClient.class),
                mock(WxpayPaymentClient.class),
                mock(StripePaymentClient.class),
                mock(PaymentWebhookService.class),
                new JsonHelper(new ObjectMapper())
        );

        PaymentOrderResponse order = order(91L, "10", "alipay", "COMPLETED", "balance", 100.0);
        when(repository.loadOrderByUserAndId(7L, 91L)).thenReturn(Optional.of(order));
        when(repository.loadProviderSnapshot(91L)).thenReturn(Map.of(
                "provider_instance_id", "12",
                "provider_key", "alipay"
        ));

        StructuredApiErrorException ex = assertThrows(
                StructuredApiErrorException.class,
                () -> service.requestRefund(new CurrentUser(7L, "user@example.test", "user", 1L), 91L, new RefundRequest("need refund"))
        );

        assertEquals(403, ex.getStatus());
        assertEquals("USER_REFUND_DISABLED", ex.getReason());
        verify(repository, never()).markRefundRequested(any(Long.class), any(Long.class), any(String.class), any(Double.class));
    }

    @Test
    void requestRefundOnlyRequiresAllowUserRefundLikeGo() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentService service = new PaymentService(
                repository,
                mock(PaymentResumeTokenService.class),
                mock(WeChatPaymentOAuthService.class),
                mock(WeChatConnectConfigService.class),
                mock(EasyPayPaymentClient.class),
                mock(AlipayPaymentClient.class),
                mock(WxpayPaymentClient.class),
                mock(StripePaymentClient.class),
                mock(PaymentWebhookService.class),
                new JsonHelper(new ObjectMapper())
        );

        PaymentOrderResponse order = order(92L, "12", "alipay", "COMPLETED", "balance", 100.0);
        ProviderInstanceResponse provider = provider(12L, "alipay", false, true);
        when(repository.loadOrderByUserAndId(7L, 92L)).thenReturn(Optional.of(order));
        when(repository.loadProviderSnapshot(92L)).thenReturn(Map.of(
                "provider_instance_id", "12",
                "provider_key", "alipay"
        ));
        when(repository.findProviderByInstanceId("12")).thenReturn(Optional.of(provider));
        when(repository.findUserBalance(7L)).thenReturn(Optional.of(150.0));
        when(repository.markRefundRequested(7L, 92L, "need refund", 100.0))
                .thenReturn(Optional.of(order(92L, "12", "alipay", "REFUND_REQUESTED", "balance", 100.0)));
        doNothing().when(repository).insertAuditLog(eq(92L), eq("REFUND_REQUESTED"), any(), eq("user:7"));

        PaymentOrderResponse result = service.requestRefund(new CurrentUser(7L, "user@example.test", "user", 1L), 92L, new RefundRequest("need refund"));

        assertEquals("REFUND_REQUESTED", result.status());
    }

    private PaymentOrderResponse order(long id, String providerInstanceId, String providerKey, String status, String orderType, double amount) {
        return new PaymentOrderResponse(
                id,
                7L,
                amount,
                amount,
                0.0,
                providerKey,
                "OUT-" + id,
                status,
                orderType,
                null,
                null,
                null,
                null,
                0.0,
                null,
                null,
                null,
                null,
                null,
                providerInstanceId,
                providerKey,
                "trade-" + id,
                null,
                null
        );
    }

    private ProviderInstanceResponse provider(long id, String providerKey, boolean refundEnabled, boolean allowUserRefund) {
        return new ProviderInstanceResponse(
                id,
                providerKey,
                providerKey + "-provider",
                Map.of(),
                List.of(providerKey),
                true,
                "redirect",
                refundEnabled,
                allowUserRefund,
                "",
                0
        );
    }
}
