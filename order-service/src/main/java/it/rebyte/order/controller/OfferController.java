package it.rebyte.order.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import it.rebyte.order.dto.CreateOfferRequest;
import it.rebyte.order.dto.OfferView;
import it.rebyte.order.dto.OfferPage;
import it.rebyte.order.service.OfferQueryService;
import it.rebyte.order.service.OfferAcceptanceService;
import it.rebyte.order.service.OfferRejectionService;
import it.rebyte.order.service.OfferService;

@RestController
@RequestMapping("/offers")
public class OfferController {
    private final OfferService service;
    private final OfferQueryService queries;
    private final OfferAcceptanceService acceptance;
    private final OfferRejectionService rejection;

    public OfferController(OfferService service, OfferQueryService queries, OfferAcceptanceService acceptance,
            OfferRejectionService rejection) {
        this.service = service;
        this.queries = queries;
        this.acceptance = acceptance;
        this.rejection = rejection;
    }

    @PostMapping("/{id}/accept")
    public OfferView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return acceptance.accept(id, jwt.getSubject());
    }

    @PostMapping("/{id}/reject")
    public OfferView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return rejection.rejectBySeller(id, jwt.getSubject());
    }

    @PostMapping("/{id}/withdraw")
    public OfferView withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.withdraw(id, jwt.getSubject());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OfferView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOfferRequest request) {
        return service.create(jwt.getSubject(), request);
    }

    @GetMapping("/{id}")
    public OfferView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        return queries.detail(id, jwt.getSubject(), groups != null && groups.contains("ADMIN"));
    }

    @GetMapping("/me/sent")
    public OfferPage sent(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String listingId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return queries.sent(jwt.getSubject(), listingId, cursor, size);
    }

    @GetMapping("/me/received")
    public OfferPage received(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String listingId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return queries.received(jwt.getSubject(), listingId, cursor, size);
    }
}
