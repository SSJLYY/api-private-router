package org.apiprivaterouter.javabackend.payment.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.apiprivaterouter.javabackend.payment.service.PaymentWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment/webhook")
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
        this.paymentWebhookService = paymentWebhookService;
    }

    @GetMapping("/easypay")
    public ResponseEntity<?> easyPayGet(HttpServletRequest request) {
        return paymentWebhookService.handle("easypay", request, request.getQueryString(), true);
    }

    @PostMapping("/easypay")
    public ResponseEntity<?> easyPayPost(HttpServletRequest request, @RequestBody(required = false) String body) {
        return paymentWebhookService.handle("easypay", request, body, false);
    }

    @PostMapping("/alipay")
    public ResponseEntity<?> alipay(HttpServletRequest request, @RequestBody(required = false) String body) {
        return paymentWebhookService.handle("alipay", request, body, false);
    }

    @PostMapping("/wxpay")
    public ResponseEntity<?> wxpay(HttpServletRequest request, @RequestBody(required = false) String body) {
        return paymentWebhookService.handle("wxpay", request, body, false);
    }

    @PostMapping("/stripe")
    public ResponseEntity<?> stripe(HttpServletRequest request, @RequestBody(required = false) String body) {
        return paymentWebhookService.handle("stripe", request, body, false);
    }
}
