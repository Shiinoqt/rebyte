package it.rebyte.messaging.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "order-service")
public interface OrderServiceClient {

    @GetMapping("/internal/licences/holders")
    LicenceHolderPage holders(@RequestParam("listingId") String listingId,
            @RequestParam(value = "cursor", required = false) String cursor, @RequestParam("size") int size);
}
