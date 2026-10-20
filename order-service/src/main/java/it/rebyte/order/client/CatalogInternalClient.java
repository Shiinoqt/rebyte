package it.rebyte.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import it.rebyte.order.dto.CatalogListingResponse;
import it.rebyte.order.dto.ReserveListingRequest;
import it.rebyte.order.dto.ReserveListingResponse;

/**
 * catalog-service's {@code /internal/listings} contract (D-01, D-09, D-13,
 * D-17): a fresh, uncached checkout snapshot and the caller-supplied,
 * idempotent reserve. Short connect/read timeouts and the circuit breaker
 * are configured per-client in {@code application.yml} (D-37).
 */
@FeignClient(name = "catalog-service")
public interface CatalogInternalClient {

    @GetMapping("/internal/listings/{id}")
    CatalogListingResponse availability(@PathVariable("id") String id);

    @PatchMapping("/internal/listings/{id}/reserve")
    ReserveListingResponse reserve(@PathVariable("id") String id, @RequestBody ReserveListingRequest request);
}
