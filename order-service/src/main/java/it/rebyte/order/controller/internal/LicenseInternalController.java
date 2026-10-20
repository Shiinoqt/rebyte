package it.rebyte.order.controller.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.dto.LicenceCheckResponse;
import it.rebyte.order.dto.LicenceHolderPage;
import it.rebyte.order.service.LicenseQueryService;

@RestController
@RequestMapping("/internal/licences")
public class LicenseInternalController {

    private final LicenseQueryService licenses;

    public LicenseInternalController(LicenseQueryService licenses) {
        this.licenses = licenses;
    }

    @GetMapping
    public LicenceCheckResponse check(@RequestParam String buyerSub, @RequestParam String listingId) {
        return licenses.check(buyerSub, listingId);
    }

    @GetMapping("/holders")
    public LicenceHolderPage holders(@RequestParam String listingId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return licenses.holders(listingId, cursor, size);
    }
}
