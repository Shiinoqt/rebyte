package it.rebyte.messaging.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "catalog-service")
public interface CatalogServiceClient {

    @GetMapping("/internal/listings/{id}")
    ListingAvailability availability(@PathVariable("id") String id);
}
