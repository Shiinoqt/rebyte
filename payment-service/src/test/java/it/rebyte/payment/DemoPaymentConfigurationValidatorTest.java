package it.rebyte.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import it.rebyte.payment.client.OrderCaptureAuthorizationClient;
import it.rebyte.payment.config.DemoPaymentConfigurationValidator;
import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentMethod;
import it.rebyte.payment.provider.PayPalClient;
import it.rebyte.payment.provider.PayPalDtos.OrderRequest;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.service.PaymentService;

@ExtendWith(OutputCaptureExtension.class)
class DemoPaymentConfigurationValidatorTest {

    private static final Map<String, String> VALID_SETTINGS = Map.of(
            "rebyte.paypal.client-id", "DUMMY_CLIENT_ID_91",
            "rebyte.paypal.client-secret", "DUMMY_CLIENT_SECRET_92",
            "rebyte.paypal.webhook-id", "DUMMY_WEBHOOK_ID_93",
            "rebyte.checkout.return-base-url", "https://rebyte.tech");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DemoPaymentConfigurationValidator.class);

    @Test
    void demoProfileLoadsExplicitSettingsAndKeepsCheckoutReturnContract() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=demo",
                        "PAYPAL_CLIENT_ID=dummy-client", "PAYPAL_CLIENT_SECRET=dummy-secret",
                        "PAYPAL_WEBHOOK_ID=dummy-webhook", "CHECKOUT_RETURN_BASE_URL=https://rebyte.tech")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DemoPaymentConfigurationValidator.class);
                    var repository = mock(PaymentRepository.class);
                    var provider = mock(PayPalClient.class);
                    var orders = mock(OrderCaptureAuthorizationClient.class);
                    var transactions = mock(PlatformTransactionManager.class);
                    var service = new PaymentService(repository, provider, orders, transactions,
                            context.getEnvironment().getRequiredProperty("rebyte.checkout.return-base-url"));
                    var payment = new Payment("order", "buyer", new BigDecimal("10.00"), BigDecimal.ZERO,
                            new BigDecimal("10.00"), "Demo listing", PaymentMethod.PAYPAL, "request", Instant.now());
                    OrderRequest request = ReflectionTestUtils.invokeMethod(service, "buildOrderRequest", payment);
                    assertThat(request.paymentSource().paypal().experienceContext().returnUrl())
                            .isEqualTo("https://rebyte.tech/checkout/return");
                    assertThat(request.paymentSource().paypal().experienceContext().cancelUrl())
                            .isEqualTo("https://rebyte.tech/checkout/cancel");
                    verifyNoInteractions(repository, provider, orders, transactions);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = { "default", "local-auth", "test" })
    void otherProfilesDoNotRequireDemoSettings(String profile) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(DemoPaymentConfigurationValidator.class));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("missingSettings")
    void missingOrBlankSettingsRefuseStartup(String property, String label, String value, CapturedOutput output) {
        var settings = new LinkedHashMap<>(VALID_SETTINGS);
        if (value == null) settings.remove(property);
        else settings.put(property, value);
        assertRefused(settings, property, output);
    }

    static Stream<Arguments> missingSettings() {
        return VALID_SETTINGS.keySet().stream().flatMap(property -> Stream.of(
                Arguments.of(property, "absent", null),
                Arguments.of(property, "empty", ""),
                Arguments.of(property, "whitespace", " \t ")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.invalid", "http://rebyte.tech", "https://localhost", "https://LOCALHOST.",
            "https://sub.localhost", "https://127.0.0.1", "https://[::1]", "//rebyte.tech", "rebyte.tech",
            "https:///checkout", "https://rebyte.tech/", "https://rebyte.tech/checkout",
            "https://ORIGIN_USER:ORIGIN_SECRET@rebyte.tech", "https://rebyte.tech?secret=ORIGIN_QUERY",
            "https://rebyte.tech#ORIGIN_FRAGMENT", "https://rebyte.tech:0", "https://rebyte.tech:65536",
            "https://rebyte.tech/ORIGIN SPACE", "https://rebyte.tech/%ZZ_ORIGIN_SECRET"
    })
    void invalidOriginsRefuseStartupWithoutLeakingValues(String origin, CapturedOutput output) {
        var settings = new LinkedHashMap<>(VALID_SETTINGS);
        settings.put("rebyte.checkout.return-base-url", origin);
        assertRefused(settings, "rebyte.checkout.return-base-url", output);
    }

    @Test
    void demoProfileHasNoFallbackFrontendOrigin(CapturedOutput output) {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=demo",
                        "PAYPAL_CLIENT_ID=DUMMY_CLIENT_ID_91", "PAYPAL_CLIENT_SECRET=DUMMY_CLIENT_SECRET_92",
                        "PAYPAL_WEBHOOK_ID=DUMMY_WEBHOOK_ID_93", "CHECKOUT_RETURN_BASE_URL=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("rebyte.checkout.return-base-url");
                    assertThat(output.getAll()).doesNotContain("DUMMY_CLIENT_ID_91", "DUMMY_CLIENT_SECRET_92",
                            "DUMMY_WEBHOOK_ID_93");
                });
    }

    private void assertRefused(Map<String, String> settings, String property, CapturedOutput output) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues(settings.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(property);
                    var trace = new StringWriter();
                    context.getStartupFailure().printStackTrace(new PrintWriter(trace));
                    for (String value : settings.values()) {
                        if (!value.isBlank()) {
                            assertThat(trace.toString()).doesNotContain(value);
                            assertThat(output.getAll()).doesNotContain(value);
                        }
                    }
                });
    }
}
