package it.rebyte.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.dto.ReviewPage;
import it.rebyte.order.dto.ReviewView;
import it.rebyte.order.service.ReviewQueryService;

/** Reviews are read-only after creation and visible to every authenticated user. */
@RestController
public class ReviewController {

    private final ReviewQueryService queryService;

    public ReviewController(ReviewQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/reviews/{id}")
    public ReviewView get(@PathVariable String id) {
        return queryService.get(id);
    }

    @GetMapping("/users/{sub}/reviews")
    public ReviewPage bySeller(@PathVariable String sub, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String listingId) {
        return queryService.bySeller(sub, cursor, size, listingId);
    }
}
