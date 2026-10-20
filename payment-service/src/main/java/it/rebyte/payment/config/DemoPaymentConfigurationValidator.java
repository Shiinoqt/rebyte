package it.rebyte.payment.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public final class DemoPaymentConfigurationValidator {

    public DemoPaymentConfigurationValidator(
            @Value("${rebyte.paypal.client-id:}") String clientId,
            @Value("${rebyte.paypal.client-secret:}") String clientSecret,
            @Value("${rebyte.paypal.webhook-id:}") String webhookId,
            @Value("${rebyte.checkout.return-base-url:}") String returnBaseUrl) {
        requireSetting("rebyte.paypal.client-id", clientId);
        requireSetting("rebyte.paypal.client-secret", clientSecret);
        requireSetting("rebyte.paypal.webhook-id", webhookId);
        requireSetting("rebyte.checkout.return-base-url", returnBaseUrl);
        URI origin;
        try {
            origin = new URI(returnBaseUrl);
        } catch (URISyntaxException invalid) {
            throw new IllegalStateException("rebyte.checkout.return-base-url");
        }
        String host = origin.getHost();
        if (!"https".equalsIgnoreCase(origin.getScheme()) || host == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !"".equals(origin.getRawPath())
                || origin.getPort() == 0 || origin.getPort() > 65535) {
            throw new IllegalStateException("rebyte.checkout.return-base-url");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".invalid")
                || host.startsWith("127.") || host.equals("[::1]")) {
            throw new IllegalStateException("rebyte.checkout.return-base-url");
        }
    }

    private static void requireSetting(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name);
    }
}
