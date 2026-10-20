package it.rebyte.user.support;

import java.util.List;
import java.util.Map;

import org.springframework.boot.resttestclient.TestRestTemplate;

/**
 * Helper di test per il minter {@code local-auth} (D-26) — ogni test di
 * integrazione di questa fase che ha bisogno di un bearer token dipende da
 * questo helper.
 */
public final class LocalAuthTokens {

    private LocalAuthTokens() {
    }

    @SuppressWarnings("unchecked")
    public static String mint(TestRestTemplate restTemplate, String sub, List<String> groups) {
        Map<String, Object> request = Map.of("sub", sub, "groups", groups);
        Map<String, Object> response = restTemplate.postForObject("/local-auth/token", request, Map.class);
        return (String) response.get("accessToken");
    }
}
