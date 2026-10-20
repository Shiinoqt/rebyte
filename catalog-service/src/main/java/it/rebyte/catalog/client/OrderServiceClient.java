package it.rebyte.catalog.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "order-service")
public interface OrderServiceClient {
    @GetMapping("/internal/licences")
    LicenceCheckResponse check(@RequestParam("buyerSub") String buyerSub,
            @RequestParam("listingId") String listingId);
}
