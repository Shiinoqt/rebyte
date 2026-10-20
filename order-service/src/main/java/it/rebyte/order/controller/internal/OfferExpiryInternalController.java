package it.rebyte.order.controller.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.service.OfferExpiryService;

@RestController
@RequestMapping("/internal/offers")
public class OfferExpiryInternalController {

    private final OfferExpiryService expiry;

    public OfferExpiryInternalController(OfferExpiryService expiry) {
        this.expiry = expiry;
    }

    @PostMapping("/expire-due")
    public ExpireDueResponse expireDue() {
        return new ExpireDueResponse(expiry.expireDue());
    }

    public record ExpireDueResponse(int expiredCount) {
    }
}
