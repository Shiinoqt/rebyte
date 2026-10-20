package it.rebyte.order.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import it.rebyte.order.dto.UserSummaryResponse;

/**
 * user-service's batch summary read, used only to label review authors. Callers
 * must tolerate failure: a review page is still served without usernames.
 */
@FeignClient(name = "user-service")
public interface UserInternalClient {

    @GetMapping("/internal/users")
    List<UserSummaryResponse> summaries(@RequestParam("subs") List<String> subs);
}
