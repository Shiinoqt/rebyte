package it.rebyte.payment.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import it.rebyte.payment.service.PayPalWebhookVerifier;
import it.rebyte.payment.service.PaymentWebhookService;

@RestController
public class PaymentWebhookController {

    private final PayPalWebhookVerifier verifier;
    private final PaymentWebhookService service;

    public PaymentWebhookController(PayPalWebhookVerifier verifier, PaymentWebhookService service) {
        this.verifier = verifier;
        this.service = service;
    }

    @PostMapping(path = "/payments/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody, @RequestHeader HttpHeaders headers) {
        if (!verifier.verify(rawBody, headers)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature");
        }
        service.acceptVerified(rawBody);
        return ResponseEntity.ok().build();
    }
}
