package it.rebyte.order.service;

import java.time.Instant;

import org.springframework.stereotype.Service;

import it.rebyte.commons.license.LicenseKeyPattern;
import it.rebyte.order.domain.License;
import it.rebyte.order.domain.Order;
import it.rebyte.order.repository.LicenseRepository;

@Service
public class LicenseService {

    private final LicenseRepository licenses;

    public LicenseService(LicenseRepository licenses) {
        this.licenses = licenses;
    }

    public License issue(Order order, Instant now) {
        var existing = licenses.findByOrderId(order.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        String pattern = LicenseKeyPattern.isValid(order.getLicenseKeyPattern())
                ? order.getLicenseKeyPattern() : LicenseKeyPattern.DEFAULT;
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = LicenseKeyPattern.generate(pattern);
            if (!licenses.existsById(candidate)) {
                return licenses.save(new License(candidate, order, now));
            }
        }
        throw new IllegalStateException("licence key space exhausted for order " + order.getId());
    }
}
