package it.rebyte.catalog.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.catalog.dto.ListingCardPage;
import it.rebyte.catalog.service.ListingCardQueryService;

@RestController
public class SellerListingsController {

    private final ListingCardQueryService cards;

    public SellerListingsController(ListingCardQueryService cards) {
        this.cards = cards;
    }

    @GetMapping("/users/{sub}/listings")
    public ListingCardPage activeListings(@PathVariable String sub, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return cards.activeBySeller(sub, cursor, size);
    }
}
