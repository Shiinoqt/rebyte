package it.rebyte.messaging.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.client.UserServiceClient;
import it.rebyte.messaging.client.UserSummary;
import it.rebyte.messaging.error.UserServiceUnavailableException;

@Service
public class UsernameLookup {

    private static final Logger log = LoggerFactory.getLogger(UsernameLookup.class);
    private static final int MAX_BATCH_SIZE = 50;

    private final UserServiceClient users;

    public UsernameLookup(UserServiceClient users) {
        this.users = users;
    }

    public String username(String sub) {
        try {
            UserSummary summary = users.getSummary(sub);
            if (summary == null || summary.username() == null || summary.username().isBlank()) {
                throw new UserServiceUnavailableException();
            }
            return summary.username();
        } catch (RuntimeException failure) {
            if (failure instanceof FeignException feign && feign.status() == 404) {
                return null;
            }
            throw new UserServiceUnavailableException();
        }
    }

    public String bestEffortUsername(String sub) {
        try {
            return username(sub);
        } catch (UserServiceUnavailableException unavailable) {
            return null;
        }
    }

    public Map<String, String> usernames(Collection<String> subs) {
        List<String> distinctSubs = subs.stream()
                .filter(sub -> sub != null && !sub.isBlank())
                .distinct()
                .toList();
        if (distinctSubs.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("At most 50 distinct user subs may be resolved at once");
        }
        if (distinctSubs.isEmpty()) {
            return Map.of();
        }

        try {
            List<UserSummary> summaries = users.getSummaries(distinctSubs);
            if (summaries == null || summaries.isEmpty()) {
                return Map.of();
            }
            Map<String, String> usernames = new LinkedHashMap<>();
            for (UserSummary summary : summaries) {
                if (summary != null && summary.sub() != null && !summary.sub().isBlank()) {
                    usernames.putIfAbsent(summary.sub(), summary.username());
                }
            }
            return usernames;
        } catch (RuntimeException failure) {
            log.warn("Username lookup failed for {} subs: {}", distinctSubs.size(),
                    failure.getClass().getSimpleName());
            return Map.of();
        }
    }
}
