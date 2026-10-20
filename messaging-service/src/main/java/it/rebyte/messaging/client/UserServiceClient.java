package it.rebyte.messaging.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/internal/users/{sub}")
    UserSummary getSummary(@PathVariable("sub") String sub);

    @GetMapping("/internal/users")
    List<UserSummary> getSummaries(@RequestParam("subs") List<String> subs);

    @GetMapping("/internal/users/{sub}/followers")
    FollowersPage followers(@PathVariable("sub") String sub,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam("limit") int limit);

    @GetMapping("/internal/users/{sub}/email")
    UserEmail email(@PathVariable("sub") String sub);
}
