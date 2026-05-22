package org.apiprivaterouter.javabackend.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apiprivaterouter.javabackend.payment.model.PaymentOrderResponse;
import org.apiprivaterouter.javabackend.payment.repository.PaymentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PaymentOrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderExpiryService.class);
    private static final int EXPIRE_BATCH_LIMIT = 200;

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public PaymentOrderExpiryService(PaymentRepository paymentRepository, PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 0L)
    public void expireTimedOutOrders() {
        int expired = 0;
        for (PaymentOrderResponse order : paymentRepository.findExpiredPendingOrders(EXPIRE_BATCH_LIMIT)) {
            try {
                PaymentOrderResponse reconciled = paymentService.reconcileOrderForSystem(order);
                if (!"PENDING".equalsIgnoreCase(reconciled.status())) {
                    continue;
                }
                if (paymentRepository.markOrderExpired(order.id()) > 0) {
                    expired++;
                    paymentRepository.insertAuditLog(order.id(), "ORDER_EXPIRED", Map.of("detail", "order expired"), "system");
                }
            } catch (Exception ex) {
                log.warn("payment order expiry failed orderId={}", order.id(), ex);
            }
        }
        if (expired > 0) {
            log.info("expired {} payment orders", expired);
        }
    }
}
